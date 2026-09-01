package dev.pumplink.protocol.link

import dev.pumplink.protocol.LogicalDeviceId

sealed interface LinkEvent {
    data class StartRequested(val paired: LogicalDeviceId) : LinkEvent
    data object StopRequested : LinkEvent
    data class ScanMatch(val logicalDeviceId: LogicalDeviceId?) : LinkEvent
    data object ScanTimeout : LinkEvent
    data object Connected : LinkEvent
    data class ConnectFailed(val error: ErrorClass) : LinkEvent
    data class Disconnected(val error: ErrorClass) : LinkEvent
    data object Bonded : LinkEvent
    data object BondFailed : LinkEvent
    data object BondLost : LinkEvent
    data object ServicesDiscovered : LinkEvent
    data class DiscoveryFailed(val error: ErrorClass) : LinkEvent
    data object ServiceChanged : LinkEvent
    data class MtuSettled(val mtu: Int) : LinkEvent
    data object CccdConfirmed : LinkEvent
    data class StatusRead(
        val logicalDeviceId: LogicalDeviceId,
        val protocolVersion: Int,
        val recordEpoch: UInt,
    ) : LinkEvent
    data object AuthSucceeded : LinkEvent
    data class AuthFailed(val reason: String, val unpaired: Boolean) : LinkEvent
    data class ReconcileDone(val unresolvedCount: Int) : LinkEvent
    data object UserVerifiedAtPump : LinkEvent
    /**
     * Re-run reconciliation from [LinkState.Suspended]. Distinct from
     * [UserVerifiedAtPump]: a timer tick is not a human attestation.
     */
    data object ReconcileRequested : LinkEvent
    data class Timeout(val state: LinkState) : LinkEvent
}
