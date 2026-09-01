package dev.pumplink.protocol

object PduCodec {

    fun encode(pdu: Pdu, sessionKey: ByteArray? = null): ByteArray {
        val mac = if (pdu.flags.auth) {
            val key = sessionKey ?: error("AUTH PDU requires a session key")
            SessionCrypto.pduMac(key, headerAndPayload(pdu))
        } else {
            null
        }
        val body = headerAndPayload(pdu) + (mac ?: ByteArray(0))
        val crc = Crc16.compute(body)
        val out = body.copyOf(body.size + ProtocolLimits.CRC_SIZE)
        out[body.size] = (crc ushr 8).toByte()
        out[body.size + 1] = crc.toByte()
        return out
    }

    fun decode(bytes: ByteArray, sessionKey: ByteArray? = null): DecodeResult {
        if (bytes.size < ProtocolLimits.MIN_UNAUTH_PDU) {
            return DecodeResult.Fail(NakReason.MALFORMED_PAYLOAD)
        }
        val crcOffset = bytes.size - ProtocolLimits.CRC_SIZE
        val expected = Crc16.compute(bytes, 0, crcOffset)
        val actual = bytes.u16(crcOffset)
        if (expected != actual) {
            return DecodeResult.Fail(NakReason.CRC_FAIL)
        }

        val version = bytes.u8(0) ushr 4
        if (version != ProtocolLimits.VERSION) {
            return DecodeResult.Fail(NakReason.UNSUPPORTED_VERSION)
        }
        val flags = Flags(bytes.u8(0) and 0x0F)
        val min = if (flags.auth) ProtocolLimits.MIN_AUTH_PDU else ProtocolLimits.MIN_UNAUTH_PDU
        if (bytes.size < min) {
            return DecodeResult.Fail(NakReason.MALFORMED_PAYLOAD)
        }
        val opcode = Opcode.fromCode(bytes.u8(1))
            ?: return DecodeResult.Fail(NakReason.UNKNOWN_OPCODE)

        val macEnd = crcOffset
        val macStart = if (flags.auth) macEnd - ProtocolLimits.MAC_SIZE else macEnd
        val payload = bytes.copyOfRangeOrEmpty(ProtocolLimits.HEADER_SIZE, macStart)
        val mac = if (flags.auth) bytes.copyOfRange(macStart, macEnd) else null

        if (mac != null) {
            val key = sessionKey ?: return DecodeResult.Fail(NakReason.NOT_AUTHENTICATED)
            val covered = bytes.copyOfRange(0, macStart)
            if (!SessionCrypto.verifyPduMac(key, covered, mac)) {
                return DecodeResult.Fail(NakReason.BAD_MAC)
            }
        }

        return DecodeResult.Ok(
            Pdu(
                version = version,
                flags = flags,
                opcode = opcode,
                seq = bytes.u8(2),
                ackSeq = bytes.u8(3),
                commandId = CommandId.fromBytes(bytes, 4),
                payload = payload,
                mac = mac,
            ),
        )
    }

    private fun headerAndPayload(pdu: Pdu): ByteArray {
        val out = ArrayList<Byte>(ProtocolLimits.HEADER_SIZE + pdu.payload.size)
        out.putU8((pdu.version shl 4) or (pdu.flags.bits and 0x0F))
        out.putU8(pdu.opcode.code)
        out.putU8(pdu.seq)
        out.putU8(pdu.ackSeq)
        out.addAll(pdu.commandId.toBytes().toList())
        pdu.payload.forEach { out.add(it) }
        return out.toByteArray()
    }
}
