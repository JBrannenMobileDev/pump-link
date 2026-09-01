package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PduCodecTest {

    @Test
    fun `round-trips an unauthenticated request`() {
        val pdu = Pdu.request(Opcode.GET_STATUS_REQ, seq = 0)
        val encoded = PduCodec.encode(pdu)
        val decoded = PduCodec.decode(encoded)
        val ok = assertIs<DecodeResult.Ok>(decoded)
        assertEquals(pdu.copy(mac = null), ok.pdu.copy(mac = null))
        assertEquals(Opcode.GET_STATUS_REQ, ok.pdu.opcode)
    }

    @Test
    fun `smallest unauthenticated PDU is ten octets`() {
        val encoded = PduCodec.encode(Pdu.request(Opcode.ACK, seq = 0, ackReq = false))
        assertEquals(ProtocolLimits.MIN_UNAUTH_PDU, encoded.size)
    }

    @Test
    fun `authenticated PDU is eighteen octets at empty payload`() {
        val key = ByteArray(32) { 7 }
        val pdu = Pdu.request(Opcode.GET_STATUS_REQ, seq = 1, auth = true)
        val encoded = PduCodec.encode(pdu, key)
        assertEquals(ProtocolLimits.MIN_AUTH_PDU, encoded.size)
        assertIs<DecodeResult.Ok>(PduCodec.decode(encoded, key))
    }

    @Test
    fun `a flipped bit fails the CRC`() {
        val encoded = PduCodec.encode(Pdu.request(Opcode.GET_STATUS_REQ, seq = 0))
        encoded[5] = (encoded[5].toInt() xor 0x01).toByte()
        val result = PduCodec.decode(encoded)
        val fail = assertIs<DecodeResult.Fail>(result)
        assertEquals(NakReason.CRC_FAIL, fail.reason)
    }

    @Test
    fun `truncation fails the CRC`() {
        val encoded = PduCodec.encode(Pdu.request(Opcode.GET_STATUS_REQ, seq = 0))
        val result = PduCodec.decode(encoded.copyOf(encoded.size - 1))
        assertIs<DecodeResult.Fail>(result)
    }

    @Test
    fun `wrong session key fails the MAC`() {
        val key = ByteArray(32) { 1 }
        val other = ByteArray(32) { 2 }
        val encoded = PduCodec.encode(Pdu.request(Opcode.GET_STATUS_REQ, seq = 3, auth = true), key)
        val fail = assertIs<DecodeResult.Fail>(PduCodec.decode(encoded, other))
        assertEquals(NakReason.BAD_MAC, fail.reason)
    }

    @Test
    fun `unsupported version is rejected`() {
        val encoded = PduCodec.encode(Pdu.request(Opcode.ACK, seq = 0, ackReq = false))
        encoded[0] = (0x20 or (encoded[0].toInt() and 0x0F)).toByte()
        val crc = Crc16.compute(encoded, 0, encoded.size - 2)
        encoded[encoded.size - 2] = (crc ushr 8).toByte()
        encoded[encoded.size - 1] = crc.toByte()
        val fail = assertIs<DecodeResult.Fail>(PduCodec.decode(encoded))
        assertEquals(NakReason.UNSUPPORTED_VERSION, fail.reason)
    }

    @Test
    fun `unknown opcode is rejected`() {
        val encoded = PduCodec.encode(Pdu.request(Opcode.ACK, seq = 0, ackReq = false))
        encoded[1] = 0x55
        val crc = Crc16.compute(encoded, 0, encoded.size - 2)
        encoded[encoded.size - 2] = (crc ushr 8).toByte()
        encoded[encoded.size - 1] = crc.toByte()
        val fail = assertIs<DecodeResult.Fail>(PduCodec.decode(encoded))
        assertEquals(NakReason.UNKNOWN_OPCODE, fail.reason)
    }

    @Test
    fun `two encodings of the same PDU are byte-identical`() {
        val key = ByteArray(32) { 9 }
        val pdu = Pdu.request(
            Opcode.BOLUS_REQ,
            seq = 4,
            commandId = CommandId(42u),
            payload = BolusRequest(1500, 60).encode(),
            auth = true,
        )
        assertTrue(PduCodec.encode(pdu, key).contentEquals(PduCodec.encode(pdu, key)))
        assertNotEquals(0, PduCodec.encode(pdu, key).size)
    }
}
