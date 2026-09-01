package dev.pumplink.data

import dev.pumplink.domain.AbortReason
import dev.pumplink.protocol.AbortCodes

object AbortReasons {
    fun fromCode(code: Int): AbortReason = when (code) {
        AbortCodes.USER_CANCELLED -> AbortReason.UserCancelled
        AbortCodes.INSUFFICIENT_RESERVOIR -> AbortReason.Reservoir
        AbortCodes.OCCLUSION -> AbortReason.Occlusion
        else -> AbortReason.PumpRejected
    }
}
