package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ReplayAcrossSessionsRejectedTest {

    @Test
    fun `a PDU captured under one session key fails MAC under another`() {
        val pairing = ByteArray(32) { 8 }
        val nonceC1 = ByteArray(16) { 1 }
        val nonceP1 = ByteArray(16) { 2 }
        val nonceC2 = ByteArray(16) { 3 }
        val nonceP2 = ByteArray(16) { 4 }
        val key1 = SessionCrypto.deriveSessionKey(pairing, nonceC1, nonceP1)
        val key2 = SessionCrypto.deriveSessionKey(pairing, nonceC2, nonceP2)
        assertNotEquals(key1.toList(), key2.toList())
        val captured = PduCodec.encode(
            Pdu.request(Opcode.BOLUS_REQ, seq = 4, commandId = CommandId(11u), auth = true),
            key1,
        )
        val fail = assertIs<DecodeResult.Fail>(PduCodec.decode(captured, key2))
        assertEquals(NakReason.BAD_MAC, fail.reason)
    }
}
