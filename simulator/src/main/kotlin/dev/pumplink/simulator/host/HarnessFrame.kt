package dev.pumplink.simulator.host

/**
 * Length-prefixed harness frames. Test fixture, not the product protocol.
 * See docs/08-harness.md.
 */
data class HarnessFrame(val type: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        val length = 1 + payload.size
        require(length <= MAX) { "harness frame $length exceeds $MAX" }
        return byteArrayOf(
            (length ushr 8).toByte(),
            length.toByte(),
            type.toByte(),
        ) + payload
    }

    override fun equals(other: Any?): Boolean =
        other is HarnessFrame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()

    companion object {
        const val MAX = 1024
        const val MTU_CHANGED = 0x01
        const val SUBSCRIBED = 0x02
        const val UNSUBSCRIBED = 0x03
        const val WRITE_RECEIVED = 0x04
        const val NOTIFY = 0x05
        const val FAULT = 0x10
        const val SET_RESERVOIR = 0x11
        const val SET_BATTERY = 0x12
        const val FORCE_DISCONNECT_UP = 0x13
        const val FORCE_DISCONNECT_DOWN = 0x14

        const val CHAR_CMD = 0x00
        const val CHAR_RSP = 0x01
        const val CHAR_STATUS = 0x02

        const val PORT = 17341
    }
}
