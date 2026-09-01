package dev.pumplink.protocol.link

import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Timeouts

/**
 * Flat `when (state)` then `when (event)` over leaf states. The composite
 * Active/Linking in the diagram is a presentation device, not a nested machine.
 */
object LinkReducer {

    fun reduce(state: LinkState, event: LinkEvent): Transition {
        if (event is LinkEvent.StopRequested) {
            return stop(state)
        }
        return when (state) {
            LinkState.Idle -> idle(event)
            is LinkState.Scanning -> scanning(state, event)
            is LinkState.Connecting -> connecting(state, event)
            is LinkState.Bonding -> bonding(state, event)
            is LinkState.Discovering -> discovering(state, event)
            is LinkState.Configuring -> configuring(state, event)
            is LinkState.Subscribed -> subscribed(state, event)
            is LinkState.Authenticating -> authenticating(state, event)
            is LinkState.Reconciling -> reconciling(state, event)
            is LinkState.Ready -> ready(state, event)
            is LinkState.Suspended -> suspended(state, event)
            is LinkState.Recovering -> recovering(state, event)
            is LinkState.Failed -> failed(state, event)
            is LinkState.Unpaired -> unpaired(state, event)
        }
    }

    private fun stop(state: LinkState): Transition {
        val paired = state.paired
        return Transition(
            LinkState.Idle,
            buildList {
                add(LinkEffect.StopScan)
                val linked = state !is LinkState.Idle &&
                    state !is LinkState.Scanning &&
                    state !is LinkState.Failed &&
                    state !is LinkState.Unpaired
                if (linked) {
                    add(LinkEffect.ResetSession)
                    add(LinkEffect.Disconnect)
                    add(LinkEffect.ReleaseGatt)
                }
                if (paired != null && state is LinkState.Scanning) {
                    add(LinkEffect.StopScan)
                }
            }.distinct(),
        )
    }

    private fun idle(event: LinkEvent): Transition = when (event) {
        is LinkEvent.StartRequested -> enterScanning(event.paired, attempts = 0)
        LinkEvent.StopRequested -> stay(LinkState.Idle)
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        is LinkEvent.Disconnected,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        is LinkEvent.Timeout,
        -> stay(LinkState.Idle)
    }

    private fun scanning(state: LinkState.Scanning, event: LinkEvent): Transition = when (event) {
        is LinkEvent.ScanMatch -> {
            val advertised = event.logicalDeviceId
            if (advertised == null || advertised == state.paired) {
                enter(LinkState.Connecting(state.paired, state.attempts), LinkEffect.StopScan, LinkEffect.Connect)
            } else {
                stay(state)
            }
        }
        LinkEvent.ScanTimeout -> Transition(LinkState.Idle, listOf(LinkEffect.StopScan))
        is LinkEvent.Timeout -> if (sameKind(event.state, state)) {
            Transition(LinkState.Idle, listOf(LinkEffect.StopScan))
        } else {
            stay(state)
        }
        is LinkEvent.StartRequested -> stay(state)
        is LinkEvent.Disconnected -> stay(state)
        LinkEvent.StopRequested,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        -> stay(state)
    }

    private fun connecting(state: LinkState.Connecting, event: LinkEvent): Transition = when (event) {
        LinkEvent.Connected -> enter(LinkState.Bonding(state.paired, state.attempts), LinkEffect.CreateBond)
        is LinkEvent.ConnectFailed -> recover(state.paired, state.attempts + 1, event.error)
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun bonding(state: LinkState.Bonding, event: LinkEvent): Transition = when (event) {
        LinkEvent.Bonded -> enter(LinkState.Discovering(state.paired, state.attempts), LinkEffect.DiscoverServices)
        LinkEvent.BondFailed -> Transition(
            LinkState.Unpaired(state.paired, "bond failed"),
            listOf(LinkEffect.Disconnect, LinkEffect.SurfaceUnpaired("bond failed")),
        )
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun discovering(state: LinkState.Discovering, event: LinkEvent): Transition = when (event) {
        LinkEvent.ServicesDiscovered -> enter(
            LinkState.Configuring(state.paired, state.attempts),
            LinkEffect.RequestMtu,
            LinkEffect.EnableNotifications,
        )
        is LinkEvent.DiscoveryFailed -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.ServiceChanged -> enter(state, LinkEffect.DiscoverServices)
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun configuring(state: LinkState.Configuring, event: LinkEvent): Transition = when (event) {
        is LinkEvent.MtuSettled -> {
            val next = state.copy(mtuSettled = true, mtu = event.mtu)
            if (next.mtuSettled && next.cccdConfirmed) {
                enter(LinkState.Subscribed(state.paired, state.attempts, event.mtu), LinkEffect.ReadStatus)
            } else {
                Transition(next)
            }
        }
        LinkEvent.CccdConfirmed -> {
            val next = state.copy(cccdConfirmed = true)
            if (next.mtuSettled && next.cccdConfirmed) {
                enter(LinkState.Subscribed(state.paired, state.attempts, state.mtu), LinkEffect.ReadStatus)
            } else {
                Transition(next)
            }
        }
        LinkEvent.ServiceChanged -> enter(
            LinkState.Discovering(state.paired, state.attempts),
            LinkEffect.DiscoverServices,
        )
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun subscribed(state: LinkState.Subscribed, event: LinkEvent): Transition = when (event) {
        is LinkEvent.StatusRead -> {
            if (event.logicalDeviceId == state.paired &&
                event.protocolVersion == ProtocolLimits.VERSION
            ) {
                enter(
                    LinkState.Authenticating(state.paired, state.attempts, state.mtu),
                    LinkEffect.BeginAuth,
                )
            } else {
                recover(state.paired, state.attempts + 1, ErrorClass.CacheStale, disconnect = true)
            }
        }
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        LinkEvent.ServiceChanged -> enter(
            LinkState.Discovering(state.paired, state.attempts),
            LinkEffect.DiscoverServices,
        )
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun authenticating(state: LinkState.Authenticating, event: LinkEvent): Transition = when (event) {
        LinkEvent.AuthSucceeded -> enter(
            LinkState.Reconciling(state.paired, state.attempts, state.mtu),
            LinkEffect.BeginReconcile,
        )
        is LinkEvent.AuthFailed -> if (event.unpaired) {
            unpaired(state.paired, event.reason)
        } else {
            recover(state.paired, state.attempts + 1, ErrorClass.AuthFailure, disconnect = true)
        }
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.Timeout -> timedOut(event, state) { recover(state.paired, state.attempts + 1, ErrorClass.TransientLink) }
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun reconciling(state: LinkState.Reconciling, event: LinkEvent): Transition = when (event) {
        is LinkEvent.ReconcileDone -> if (event.unresolvedCount == 0) {
            Transition(LinkState.Ready(state.paired, state.mtu))
        } else {
            Transition(LinkState.Suspended(state.paired, state.mtu))
        }
        is LinkEvent.Timeout -> timedOut(event, state) {
            Transition(LinkState.Suspended(state.paired, state.mtu))
        }
        is LinkEvent.Disconnected -> recover(state.paired, state.attempts + 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun ready(state: LinkState.Ready, event: LinkEvent): Transition = when (event) {
        is LinkEvent.Disconnected -> recover(state.paired, attempts = 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        LinkEvent.ServiceChanged -> enter(
            LinkState.Discovering(state.paired, attempts = 0),
            LinkEffect.DiscoverServices,
        )
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        is LinkEvent.Timeout,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun suspended(state: LinkState.Suspended, event: LinkEvent): Transition = when (event) {
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        -> enter(
            LinkState.Reconciling(state.paired, attempts = 0, state.mtu),
            LinkEffect.BeginReconcile,
        )
        is LinkEvent.Disconnected -> recover(state.paired, attempts = 1, event.error)
        LinkEvent.BondLost -> unpaired(state.paired, "bond lost")
        is LinkEvent.StartRequested,
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        is LinkEvent.Timeout,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun recovering(state: LinkState.Recovering, event: LinkEvent): Transition = when (event) {
        is LinkEvent.Timeout -> timedOut(event, state) { leaveRecovering(state) }
        is LinkEvent.StartRequested -> enterScanning(event.paired, attempts = 0)
        is LinkEvent.Disconnected -> stay(state)
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun failed(state: LinkState.Failed, event: LinkEvent): Transition = when (event) {
        is LinkEvent.StartRequested -> enterScanning(event.paired, attempts = 0)
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        is LinkEvent.Disconnected,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        is LinkEvent.Timeout,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun unpaired(state: LinkState.Unpaired, event: LinkEvent): Transition = when (event) {
        is LinkEvent.StartRequested -> enterScanning(event.paired, attempts = 0)
        is LinkEvent.ScanMatch,
        LinkEvent.ScanTimeout,
        LinkEvent.Connected,
        is LinkEvent.ConnectFailed,
        is LinkEvent.Disconnected,
        LinkEvent.Bonded,
        LinkEvent.BondFailed,
        LinkEvent.BondLost,
        LinkEvent.ServicesDiscovered,
        is LinkEvent.DiscoveryFailed,
        LinkEvent.ServiceChanged,
        is LinkEvent.MtuSettled,
        LinkEvent.CccdConfirmed,
        is LinkEvent.StatusRead,
        LinkEvent.AuthSucceeded,
        is LinkEvent.AuthFailed,
        is LinkEvent.ReconcileDone,
        LinkEvent.UserVerifiedAtPump,
        LinkEvent.ReconcileRequested,
        is LinkEvent.Timeout,
        LinkEvent.StopRequested,
        -> stay(state)
    }

    private fun enterScanning(paired: LogicalDeviceId, attempts: Int): Transition {
        val next = LinkState.Scanning(paired, attempts)
        return Transition(next, listOf(LinkEffect.StartScan(paired), LinkEffect.ArmTimeout(next, Timeouts.SCAN_MS)))
    }

    private fun enter(next: LinkState, vararg extra: LinkEffect): Transition {
        val timeout = timeoutFor(next)
        val effects = buildList {
            addAll(extra)
            if (timeout > 0L) add(LinkEffect.ArmTimeout(next, timeout))
        }
        return Transition(next, effects)
    }

    private fun recover(
        paired: LogicalDeviceId,
        attempts: Int,
        error: ErrorClass,
        disconnect: Boolean = false,
    ): Transition {
        if (attempts >= Timeouts.LINK_MAX_ATTEMPTS) {
            return Transition(
                LinkState.Failed(paired, ErrorClass.Unrecoverable),
                buildList {
                    add(LinkEffect.ResetSession)
                    if (disconnect) add(LinkEffect.Disconnect)
                    add(LinkEffect.ReleaseGatt)
                    add(LinkEffect.SurfaceFailed(ErrorClass.Unrecoverable))
                },
            )
        }
        val next = LinkState.Recovering(paired, attempts, error)
        return Transition(
            next,
            buildList {
                add(LinkEffect.ResetSession)
                if (disconnect) add(LinkEffect.Disconnect)
                add(LinkEffect.ReleaseGatt)
                add(LinkEffect.ArmBackoff(attempts, backoffMillis(attempts)))
            },
        )
    }

    private fun leaveRecovering(state: LinkState.Recovering): Transition =
        if (state.attempts >= Timeouts.LINK_MAX_ATTEMPTS) {
            Transition(
                LinkState.Failed(state.paired, ErrorClass.Unrecoverable),
                listOf(LinkEffect.SurfaceFailed(ErrorClass.Unrecoverable)),
            )
        } else {
            enterScanning(state.paired, state.attempts)
        }

    private fun unpaired(paired: LogicalDeviceId, reason: String): Transition =
        Transition(
            LinkState.Unpaired(paired, reason),
            listOf(
                LinkEffect.ResetSession,
                LinkEffect.Disconnect,
                LinkEffect.ReleaseGatt,
                LinkEffect.SurfaceUnpaired(reason),
            ),
        )

    private fun timedOut(
        event: LinkEvent.Timeout,
        current: LinkState,
        onMatch: () -> Transition,
    ): Transition = if (sameKind(event.state, current)) onMatch() else stay(current)

    private fun sameKind(a: LinkState, b: LinkState): Boolean = a::class == b::class
}
