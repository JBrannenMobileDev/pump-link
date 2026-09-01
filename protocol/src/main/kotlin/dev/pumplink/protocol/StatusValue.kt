package dev.pumplink.protocol

/**
 * L0 STATUS characteristic value. Readable without a session.
 * Distinct from GET_STATUS_RSP.
 */
data class StatusValue(
    val protocolVersion: Int,
    val logicalDeviceId: LogicalDeviceId,
    val recordEpoch: UInt,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>(ProtocolLimits.STATUS_VALUE_SIZE)
        out.putU8(protocolVersion)
        out.addAll(logicalDeviceId.bytes.toList())
        out.putU32(recordEpoch)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): StatusValue {
            require(bytes.size == ProtocolLimits.STATUS_VALUE_SIZE) {
                "STATUS value is ${ProtocolLimits.STATUS_VALUE_SIZE} octets, was ${bytes.size}"
            }
            return StatusValue(
                protocolVersion = bytes.u8(0),
                logicalDeviceId = LogicalDeviceId.fromBytes(bytes, 1),
                recordEpoch = bytes.u32(17),
            )
        }
    }
}

data class GetStatusPayload(
    val reservoirMilliunits: Int,
    val batteryPercent: Int,
    val deliveryActive: Boolean,
    val recordEpoch: UInt,
    val storeInstanceId: StoreInstanceId,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>(16)
        out.putU16(reservoirMilliunits)
        out.putU8(batteryPercent)
        out.putU8(if (deliveryActive) 1 else 0)
        out.putU32(recordEpoch)
        out.addAll(storeInstanceId.toBytes().toList())
        return out.toByteArray()
    }

    companion object {
        const val SIZE = 16

        fun decode(bytes: ByteArray, offset: Int = 0): GetStatusPayload {
            require(bytes.size - offset >= SIZE) { "GET_STATUS_RSP payload is $SIZE octets" }
            return GetStatusPayload(
                reservoirMilliunits = bytes.u16(offset),
                batteryPercent = bytes.u8(offset + 2),
                deliveryActive = bytes.u8(offset + 3) != 0,
                recordEpoch = bytes.u32(offset + 4),
                storeInstanceId = StoreInstanceId.fromBytes(bytes, offset + 8),
            )
        }
    }
}

data class OutcomePayload(
    val outcome: CommandOutcome,
    val record: DeliveryRecord,
    val oldestRetainedCommandId: CommandId,
    val storeInstanceId: StoreInstanceId,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>(1 + ProtocolLimits.DELIVERY_RECORD_SIZE + 4 + 8)
        out.putU8(outcome.code)
        out.addAll(record.encode().toList())
        out.addAll(oldestRetainedCommandId.toBytes().toList())
        out.addAll(storeInstanceId.toBytes().toList())
        return out.toByteArray()
    }

    companion object {
        val SIZE = 1 + ProtocolLimits.DELIVERY_RECORD_SIZE + 4 + 8

        fun decode(bytes: ByteArray, offset: Int = 0): OutcomePayload {
            require(bytes.size - offset >= SIZE) { "QUERY_COMMAND_OUTCOME_RSP payload is $SIZE octets" }
            val outcome = CommandOutcome.fromCode(bytes.u8(offset))
                ?: error("unknown outcome ${bytes.u8(offset)}")
            return OutcomePayload(
                outcome = outcome,
                record = DeliveryRecord.decode(bytes, offset + 1),
                oldestRetainedCommandId = CommandId.fromBytes(bytes, offset + 1 + ProtocolLimits.DELIVERY_RECORD_SIZE),
                storeInstanceId = StoreInstanceId.fromBytes(
                    bytes,
                    offset + 1 + ProtocolLimits.DELIVERY_RECORD_SIZE + 4,
                ),
            )
        }
    }
}

data class BolusRequest(
    val requestedMilliunits: Int,
    val maxDurationSeconds: Int,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>(4)
        out.putU16(requestedMilliunits)
        out.putU16(maxDurationSeconds)
        return out.toByteArray()
    }

    companion object {
        const val SIZE = 4

        fun decode(bytes: ByteArray, offset: Int = 0): BolusRequest {
            require(bytes.size - offset >= SIZE) { "BOLUS_REQ payload is $SIZE octets" }
            return BolusRequest(
                requestedMilliunits = bytes.u16(offset),
                maxDurationSeconds = bytes.u16(offset + 2),
            )
        }
    }
}
