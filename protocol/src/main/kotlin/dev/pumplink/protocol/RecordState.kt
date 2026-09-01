package dev.pumplink.protocol

enum class RecordState(val code: Int) {
    ACCEPTED(0x01),
    IN_PROGRESS(0x02),
    COMPLETED(0x03),
    ABORTED(0x04),
    ;

    companion object {
        fun fromCode(code: Int): RecordState? = entries.firstOrNull { it.code == code }
    }
}
