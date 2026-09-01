package dev.pumplink.protocol

/**
 * `DeliveryRecord.abortReason` codes. Valid when `state = ABORTED`.
 * Documented in docs/02-protocol.md.
 */
object AbortCodes {
    const val USER_CANCELLED = 1
    const val INVALID_DOSE = 2
    const val EXCEEDS_MAX = 3
    const val BAD_INCREMENT = 4
    const val INSUFFICIENT_RESERVOIR = 5
    const val EXCEEDS_DURATION = 6
    const val DELIVERY_ACTIVE = 7
    const val OCCLUSION = 8
}
