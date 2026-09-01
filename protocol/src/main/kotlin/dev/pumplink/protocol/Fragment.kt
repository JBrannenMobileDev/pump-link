package dev.pumplink.protocol

@JvmInline
value class FragmentHeader(val byte: Int) {
    val first: Boolean get() = byte and FIRST != 0
    val last: Boolean get() = byte and LAST != 0
    val index: Int get() = byte and INDEX_MASK

    companion object {
        const val FIRST = 0x80
        const val LAST = 0x40
        const val INDEX_MASK = 0x0F

        fun of(first: Boolean, last: Boolean, index: Int): FragmentHeader {
            require(index in 0..15) { "fragment index $index is outside 0..15" }
            var bits = index and INDEX_MASK
            if (first) bits = bits or FIRST
            if (last) bits = bits or LAST
            return FragmentHeader(bits)
        }
    }
}

object Fragmenter {

    fun payloadMax(mtu: Int): Int {
        require(mtu >= ProtocolLimits.MIN_ATT_MTU) { "MTU $mtu is below the ATT minimum" }
        return mtu - ProtocolLimits.ATT_HEADER - ProtocolLimits.FRAGMENT_HEADER
    }

    fun fragment(message: ByteArray, mtu: Int): List<ByteArray> {
        require(message.size <= ProtocolLimits.MAX_MESSAGE_SIZE) {
            "message ${message.size} exceeds ${ProtocolLimits.MAX_MESSAGE_SIZE}"
        }
        val max = payloadMax(mtu)
        if (message.isEmpty()) {
            return listOf(byteArrayOf(FragmentHeader.of(first = true, last = true, index = 0).byte.toByte()))
        }
        val fragments = ArrayList<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < message.size) {
            val remaining = message.size - offset
            val take = minOf(max, remaining)
            val first = offset == 0
            val last = take == remaining
            val header = FragmentHeader.of(first, last, index and INDEX_MASK)
            val fragment = ByteArray(1 + take)
            fragment[0] = header.byte.toByte()
            message.copyInto(fragment, 1, offset, offset + take)
            fragments.add(fragment)
            offset += take
            index += 1
        }
        return fragments
    }

    private const val INDEX_MASK = 0x0F
}

sealed interface ReassemblyResult {
    data class Complete(val message: ByteArray) : ReassemblyResult {
        override fun equals(other: Any?): Boolean =
            other is Complete && message.contentEquals(other.message)

        override fun hashCode(): Int = message.contentHashCode()
    }

    data object Incomplete : ReassemblyResult
    data object Discarded : ReassemblyResult
}

class Reassembler {
    private var buffer: ByteArray? = null
    private var expectedIndex: Int = 0
    private var length: Int = 0

    fun accept(fragment: ByteArray): ReassemblyResult {
        if (fragment.isEmpty()) {
            reset()
            return ReassemblyResult.Discarded
        }
        val header = FragmentHeader(fragment[0].toInt() and 0xFF)
        val payload = fragment.copyOfRangeOrEmpty(1, fragment.size)

        if (header.first) {
            buffer = ByteArray(ProtocolLimits.MAX_MESSAGE_SIZE)
            length = 0
            expectedIndex = 0
        }

        val open = buffer
        if (open == null) {
            return ReassemblyResult.Discarded
        }
        if (header.index != expectedIndex) {
            reset()
            return ReassemblyResult.Discarded
        }
        if (length + payload.size > ProtocolLimits.MAX_MESSAGE_SIZE) {
            reset()
            return ReassemblyResult.Discarded
        }
        payload.copyInto(open, length)
        length += payload.size
        expectedIndex = (expectedIndex + 1) and 0x0F

        return if (header.last) {
            val message = open.copyOf(length)
            reset()
            ReassemblyResult.Complete(message)
        } else {
            ReassemblyResult.Incomplete
        }
    }

    fun reset() {
        buffer = null
        expectedIndex = 0
        length = 0
    }
}
