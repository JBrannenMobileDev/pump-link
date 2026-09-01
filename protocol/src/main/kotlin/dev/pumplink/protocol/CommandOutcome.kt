package dev.pumplink.protocol

enum class CommandOutcome(val code: Int) {
    NEVER_SEEN(0x00),
    ACCEPTED(0x01),
    IN_PROGRESS(0x02),
    COMPLETED(0x03),
    ABORTED(0x04),
    EVICTED(0x05),
    STORE_REPLACED(0x06),
    ;

    companion object {
        fun fromCode(code: Int): CommandOutcome? = entries.firstOrNull { it.code == code }
    }
}
