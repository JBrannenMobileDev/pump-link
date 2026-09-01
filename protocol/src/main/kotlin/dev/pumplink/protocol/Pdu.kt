package dev.pumplink.protocol

data class Pdu(
    val version: Int = ProtocolLimits.VERSION,
    val flags: Flags,
    val opcode: Opcode,
    val seq: Int,
    val ackSeq: Int,
    val commandId: CommandId,
    val payload: ByteArray,
    val mac: ByteArray? = null,
) {
    init {
        require(version in 0..0x0F) { "version nibble out of range: $version" }
        require(seq in 0..255) { "seq out of range: $seq" }
        require(ackSeq in 0..255) { "ackSeq out of range: $ackSeq" }
        require(payload.size <= ProtocolLimits.MAX_MESSAGE_SIZE - ProtocolLimits.MIN_AUTH_PDU) {
            "payload ${payload.size} exceeds maximum"
        }
        if (mac != null) {
            require(flags.auth && mac.size == ProtocolLimits.MAC_SIZE) {
                "MAC is present iff AUTH and is 8 octets"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Pdu) return false
        return version == other.version &&
            flags == other.flags &&
            opcode == other.opcode &&
            seq == other.seq &&
            ackSeq == other.ackSeq &&
            commandId == other.commandId &&
            payload.contentEquals(other.payload) &&
            mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + flags.hashCode()
        result = 31 * result + opcode.hashCode()
        result = 31 * result + seq
        result = 31 * result + ackSeq
        result = 31 * result + commandId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (mac?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        fun request(
            opcode: Opcode,
            seq: Int,
            commandId: CommandId = CommandId.NONE,
            payload: ByteArray = ByteArray(0),
            ackReq: Boolean = true,
            auth: Boolean = false,
            mac: ByteArray? = null,
        ): Pdu = Pdu(
            flags = Flags.of(auth = auth, ackReq = ackReq, resp = false),
            opcode = opcode,
            seq = seq,
            ackSeq = ProtocolLimits.ACK_SEQ_NONE,
            commandId = commandId,
            payload = payload,
            mac = mac,
        )

        fun response(
            opcode: Opcode,
            seq: Int,
            ackSeq: Int,
            commandId: CommandId = CommandId.NONE,
            payload: ByteArray = ByteArray(0),
            auth: Boolean = false,
            mac: ByteArray? = null,
        ): Pdu = Pdu(
            flags = Flags.of(auth = auth, ackReq = false, resp = true),
            opcode = opcode,
            seq = seq,
            ackSeq = ackSeq,
            commandId = commandId,
            payload = payload,
            mac = mac,
        )
    }
}

sealed interface DecodeResult {
    data class Ok(val pdu: Pdu) : DecodeResult
    data class Fail(val reason: NakReason) : DecodeResult
}
