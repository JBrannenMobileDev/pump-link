package dev.pumplink.protocol

data class DeliveryRecord(
    val commandId: CommandId,
    val state: RecordState,
    val requestedMilliunits: Int,
    val deliveredMilliunits: Int,
    val abortReason: Int,
    val startedAt: UInt,
    val endedAt: UInt,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>(ProtocolLimits.DELIVERY_RECORD_SIZE)
        out.addAll(commandId.toBytes().toList())
        out.putU8(state.code)
        out.putU16(requestedMilliunits)
        out.putU16(deliveredMilliunits)
        out.putU8(abortReason)
        out.putU32(startedAt)
        out.putU32(endedAt)
        return out.toByteArray()
    }

    companion object {
        val EMPTY = DeliveryRecord(
            commandId = CommandId.NONE,
            state = RecordState.ACCEPTED,
            requestedMilliunits = 0,
            deliveredMilliunits = 0,
            abortReason = 0,
            startedAt = 0u,
            endedAt = 0u,
        )

        fun decode(bytes: ByteArray, offset: Int = 0): DeliveryRecord {
            require(bytes.size - offset >= ProtocolLimits.DELIVERY_RECORD_SIZE) {
                "delivery record is ${ProtocolLimits.DELIVERY_RECORD_SIZE} octets"
            }
            val state = RecordState.fromCode(bytes.u8(offset + 4))
                ?: error("unknown record state ${bytes.u8(offset + 4)}")
            return DeliveryRecord(
                commandId = CommandId.fromBytes(bytes, offset),
                state = state,
                requestedMilliunits = bytes.u16(offset + 5),
                deliveredMilliunits = bytes.u16(offset + 7),
                abortReason = bytes.u8(offset + 9),
                startedAt = bytes.u32(offset + 10),
                endedAt = bytes.u32(offset + 14),
            )
        }
    }
}
