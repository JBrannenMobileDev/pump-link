package dev.pumplink.protocol

object ProtocolLimits {
    const val VERSION = 0x1
    const val HEADER_SIZE = 8
    const val CRC_SIZE = 2
    const val MAC_SIZE = 8
    const val AUTH_MAC_SIZE = 16
    const val MAX_MESSAGE_SIZE = 512
    const val MIN_ATT_MTU = 23
    const val MAX_ATT_MTU = 517
    const val ATT_HEADER = 3
    const val FRAGMENT_HEADER = 1
    const val MIN_UNAUTH_PDU = HEADER_SIZE + CRC_SIZE
    const val MIN_AUTH_PDU = HEADER_SIZE + MAC_SIZE + CRC_SIZE
    const val DELIVERY_RECORD_SIZE = 18
    const val STATUS_VALUE_SIZE = 21
    const val MIN_RETAINED_RECORDS = 512
    const val ACK_SEQ_NONE = 0xFF
    const val SEQ_MODULUS = 256
}
