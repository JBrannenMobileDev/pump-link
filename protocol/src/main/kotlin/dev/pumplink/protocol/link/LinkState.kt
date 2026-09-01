package dev.pumplink.protocol.link

import dev.pumplink.protocol.LogicalDeviceId

sealed interface LinkState {
    val paired: LogicalDeviceId? get() = null
    val attempts: Int get() = 0
    val mtu: Int get() = 0

    data object Idle : LinkState

    data class Scanning(
        override val paired: LogicalDeviceId,
        override val attempts: Int = 0,
    ) : LinkState

    data class Connecting(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
    ) : LinkState

    data class Bonding(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
    ) : LinkState

    data class Discovering(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
    ) : LinkState

    data class Configuring(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
        val mtuSettled: Boolean = false,
        val cccdConfirmed: Boolean = false,
        override val mtu: Int = 0,
    ) : LinkState

    data class Subscribed(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
        override val mtu: Int,
    ) : LinkState

    data class Authenticating(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
        override val mtu: Int,
    ) : LinkState

    data class Reconciling(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
        override val mtu: Int,
    ) : LinkState

    data class Ready(
        override val paired: LogicalDeviceId,
        override val mtu: Int,
    ) : LinkState {
        override val attempts: Int get() = 0
    }

    data class Suspended(
        override val paired: LogicalDeviceId,
        override val mtu: Int,
    ) : LinkState

    data class Recovering(
        override val paired: LogicalDeviceId,
        override val attempts: Int,
        val error: ErrorClass,
    ) : LinkState

    data class Failed(
        override val paired: LogicalDeviceId,
        val error: ErrorClass,
    ) : LinkState

    data class Unpaired(
        override val paired: LogicalDeviceId,
        val reason: String,
    ) : LinkState
}
