package dev.pumplink.protocol.link

import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.Timeouts

sealed interface LinkEffect {
    data class StartScan(val paired: LogicalDeviceId) : LinkEffect
    data object StopScan : LinkEffect
    data object Connect : LinkEffect
    data object Disconnect : LinkEffect
    data object CreateBond : LinkEffect
    data object DiscoverServices : LinkEffect
    data object RequestMtu : LinkEffect
    data object EnableNotifications : LinkEffect
    data object ReadStatus : LinkEffect
    data object BeginAuth : LinkEffect
    data object BeginReconcile : LinkEffect
    data object ResetSession : LinkEffect
    data object ReleaseGatt : LinkEffect
    data class ArmTimeout(val state: LinkState, val millis: Long) : LinkEffect
    data class ArmBackoff(val attempts: Int, val millis: Long) : LinkEffect
    data class SurfaceFailed(val error: ErrorClass) : LinkEffect
    data class SurfaceUnpaired(val reason: String) : LinkEffect
}

data class Transition(
    val state: LinkState,
    val effects: List<LinkEffect> = emptyList(),
)

internal fun stay(state: LinkState): Transition = Transition(state)

internal fun timeoutFor(state: LinkState): Long = when (state) {
    is LinkState.Scanning -> Timeouts.SCAN_MS
    is LinkState.Connecting -> Timeouts.CONNECT_MS
    is LinkState.Bonding -> Timeouts.BOND_MS
    is LinkState.Discovering -> Timeouts.DISCOVER_MS
    is LinkState.Configuring -> Timeouts.CONFIGURE_MS
    is LinkState.Subscribed -> Timeouts.SUBSCRIBED_MS
    is LinkState.Authenticating -> Timeouts.T_AUTH_MS
    is LinkState.Reconciling -> Timeouts.T_RESOLVE_MS
    is LinkState.Idle,
    is LinkState.Ready,
    is LinkState.Suspended,
    is LinkState.Recovering,
    is LinkState.Failed,
    is LinkState.Unpaired,
    -> 0L
}

internal fun backoffMillis(attempts: Int): Long {
    val index = (attempts - 1).coerceIn(0, Timeouts.LINK_BACKOFF_MS.lastIndex)
    return Timeouts.LINK_BACKOFF_MS[index]
}
