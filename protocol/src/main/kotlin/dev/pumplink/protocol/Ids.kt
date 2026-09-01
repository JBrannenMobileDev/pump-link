package dev.pumplink.protocol

@JvmInline
value class CommandId(val value: UInt) : Comparable<CommandId> {
    override fun compareTo(other: CommandId): Int = value.compareTo(other.value)

    fun toBytes(): ByteArray = ByteArray(SIZE).also { out ->
        val v = value
        out[0] = (v shr 24).toByte()
        out[1] = (v shr 16).toByte()
        out[2] = (v shr 8).toByte()
        out[3] = v.toByte()
    }

    companion object {
        const val SIZE = 4
        val NONE = CommandId(0u)

        fun fromBytes(bytes: ByteArray, offset: Int = 0): CommandId =
            CommandId(bytes.u32(offset))
    }
}

@JvmInline
value class StoreInstanceId(val value: ULong) {
    fun toBytes(): ByteArray {
        val out = ArrayList<Byte>(SIZE)
        out.putU64(value)
        return out.toByteArray()
    }

    companion object {
        const val SIZE = 8
        val UNKNOWN = StoreInstanceId(0uL)

        fun fromBytes(bytes: ByteArray, offset: Int = 0): StoreInstanceId =
            StoreInstanceId(bytes.u64(offset))
    }
}

class LogicalDeviceId(bytes: ByteArray) {
    val bytes: ByteArray

    init {
        require(bytes.size == SIZE) { "logical device id must be $SIZE octets, was ${bytes.size}" }
        this.bytes = bytes.copyOf()
    }

    override fun equals(other: Any?): Boolean =
        other is LogicalDeviceId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val SIZE = 16

        fun fromBytes(bytes: ByteArray, offset: Int = 0): LogicalDeviceId =
            LogicalDeviceId(bytes.copyOfRange(offset, offset + SIZE))
    }
}
