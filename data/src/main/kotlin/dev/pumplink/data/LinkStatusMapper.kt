package dev.pumplink.data

import dev.pumplink.domain.LinkFault
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.LinkStep
import dev.pumplink.protocol.Timeouts
import dev.pumplink.protocol.link.ErrorClass
import dev.pumplink.protocol.link.LinkState

/**
 * The platform boundary between the transport state machine and the domain.
 *
 * [map] is what dosing consults, and it deliberately collapses the eight
 * linking substates into one value: the rule for whether a bolus may be sent
 * must not acquire a new case every time the transport learns a failure mode.
 *
 * [progress] is what the link screen consults, and it keeps the detail. Both
 * read the same [LinkState]; neither is derived from the other.
 */
object LinkStatusMapper {
    fun map(state: LinkState): LinkStatus = when (state) {
        LinkState.Idle -> LinkStatus.Idle
        is LinkState.Scanning,
        is LinkState.Connecting,
        is LinkState.Bonding,
        is LinkState.Discovering,
        is LinkState.Configuring,
        is LinkState.Subscribed,
        is LinkState.Authenticating,
        is LinkState.Reconciling,
        -> LinkStatus.Linking
        is LinkState.Ready -> LinkStatus.Ready
        is LinkState.Suspended -> LinkStatus.Suspended
        is LinkState.Recovering -> LinkStatus.Recovering
        is LinkState.Failed -> LinkStatus.Failed
        is LinkState.Unpaired -> LinkStatus.Unpaired
    }

    fun progress(state: LinkState): LinkProgress = LinkProgress(
        step = step(state),
        attempts = state.attempts,
        mtu = state.mtu,
        fault = fault(state),
        timeoutMillis = timeout(state),
    )

    private fun step(state: LinkState): LinkStep? = when (state) {
        is LinkState.Scanning -> LinkStep.Scanning
        is LinkState.Connecting -> LinkStep.Connecting
        is LinkState.Bonding -> LinkStep.Bonding
        is LinkState.Discovering -> LinkStep.Discovering
        is LinkState.Configuring -> LinkStep.Configuring
        is LinkState.Subscribed -> LinkStep.Subscribed
        is LinkState.Authenticating -> LinkStep.Authenticating
        is LinkState.Reconciling -> LinkStep.Reconciling
        LinkState.Idle,
        is LinkState.Ready,
        is LinkState.Suspended,
        is LinkState.Recovering,
        is LinkState.Failed,
        is LinkState.Unpaired,
        -> null
    }

    private fun timeout(state: LinkState): Long = when (state) {
        is LinkState.Scanning -> Timeouts.SCAN_MS
        is LinkState.Connecting -> Timeouts.CONNECT_MS
        is LinkState.Bonding -> Timeouts.BOND_MS
        is LinkState.Discovering -> Timeouts.DISCOVER_MS
        is LinkState.Configuring -> Timeouts.CONFIGURE_MS
        is LinkState.Subscribed -> Timeouts.SUBSCRIBED_MS
        is LinkState.Authenticating -> Timeouts.T_AUTH_MS
        is LinkState.Reconciling -> Timeouts.T_RESOLVE_MS
        LinkState.Idle,
        is LinkState.Ready,
        is LinkState.Suspended,
        is LinkState.Recovering,
        is LinkState.Failed,
        is LinkState.Unpaired,
        -> 0L
    }

    private fun fault(state: LinkState): LinkFault? = when (state) {
        is LinkState.Recovering -> state.error.toDomain()
        is LinkState.Failed -> state.error.toDomain()
        LinkState.Idle,
        is LinkState.Scanning,
        is LinkState.Connecting,
        is LinkState.Bonding,
        is LinkState.Discovering,
        is LinkState.Configuring,
        is LinkState.Subscribed,
        is LinkState.Authenticating,
        is LinkState.Reconciling,
        is LinkState.Ready,
        is LinkState.Suspended,
        -> null
        is LinkState.Unpaired -> LinkFault.AuthFailure
    }

    private fun ErrorClass.toDomain(): LinkFault = when (this) {
        ErrorClass.TransientLink -> LinkFault.TransientLink
        ErrorClass.PeerInitiated -> LinkFault.PeerInitiated
        ErrorClass.StackFault -> LinkFault.StackFault
        ErrorClass.CacheStale -> LinkFault.CacheStale
        ErrorClass.AuthFailure -> LinkFault.AuthFailure
        ErrorClass.ProtocolFault -> LinkFault.ProtocolFault
        ErrorClass.Unrecoverable -> LinkFault.Unrecoverable
    }
}
