package dev.pumplink.protocol

enum class NakReason(val code: Int) {
    CRC_FAIL(0x01),
    BAD_MAC(0x02),
    SEQ_OUT_OF_WINDOW(0x03),
    REASSEMBLY_ERROR(0x04),
    UNKNOWN_OPCODE(0x05),
    NOT_AUTHENTICATED(0x06),
    BUSY(0x07),
    MALFORMED_PAYLOAD(0x08),
    UNSUPPORTED_VERSION(0x09),
    ;

    companion object {
        fun fromCode(code: Int): NakReason? = entries.firstOrNull { it.code == code }
    }
}
