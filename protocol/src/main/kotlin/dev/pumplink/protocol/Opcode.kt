package dev.pumplink.protocol

enum class Opcode(val code: Int) {
    AUTH_CHALLENGE_REQ(0x01),
    AUTH_CHALLENGE_RSP(0x02),
    AUTH_VERIFY_REQ(0x03),
    AUTH_VERIFY_RSP(0x04),
    SESSION_END(0x05),
    GET_STATUS_REQ(0x10),
    GET_STATUS_RSP(0x11),
    BOLUS_REQ(0x20),
    BOLUS_RSP(0x21),
    BOLUS_CANCEL_REQ(0x22),
    BOLUS_CANCEL_RSP(0x23),
    BOLUS_PROGRESS_IND(0x24),
    QUERY_COMMAND_OUTCOME_REQ(0x30),
    QUERY_COMMAND_OUTCOME_RSP(0x31),
    GET_HISTORY_REQ(0x32),
    GET_HISTORY_RSP(0x33),
    ACK(0x7E),
    NAK(0x7F),
    ;

    companion object {
        fun fromCode(code: Int): Opcode? = entries.firstOrNull { it.code == code }
    }
}
