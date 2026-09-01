package dev.pumplink.simulator

enum class Fault(val code: Int) {
    NONE(0x00),
    DROP_NEXT_RESPONSE(0x01),
    DROP_ALL_RESPONSES(0x02),
    CORRUPT_CRC(0x03),
    DELAY_PAST_T_RESP(0x04),
    DUPLICATE_NOTIFY(0x05),
    EVICT_ALL(0x06),
    RESET_STORE(0x07),
    DISCONNECT_AFTER_ACCEPT(0x08),
    ;

    companion object {
        fun fromCode(code: Int): Fault? = entries.firstOrNull { it.code == code }
    }
}

/** Faults that rewrite or swallow the next encoded reply. */
internal fun Fault.shapesReplies(): Boolean = when (this) {
    Fault.DROP_NEXT_RESPONSE,
    Fault.DROP_ALL_RESPONSES,
    Fault.CORRUPT_CRC,
    Fault.DELAY_PAST_T_RESP,
    Fault.DUPLICATE_NOTIFY,
    -> true
    Fault.NONE,
    Fault.EVICT_ALL,
    Fault.RESET_STORE,
    Fault.DISCONNECT_AFTER_ACCEPT,
    -> false
}
