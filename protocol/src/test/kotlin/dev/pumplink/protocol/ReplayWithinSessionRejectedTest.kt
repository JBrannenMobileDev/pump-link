package dev.pumplink.protocol

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplayWithinSessionRejectedTest {

    private val pairingKey = ByteArray(32) { 5 }
    private val localId = LogicalDeviceId(ByteArray(16) { 6 })

    @Test
    fun `a byte-identical inbound repeat is dropped, not consumed as a later answer`() = runTest {
        val transport = TestTransport()
        val session = Session(transport, pairingKey, localId)
        val first = GetStatusPayload(40_000, 70, false, 0u, StoreInstanceId(1uL))
        val second = GetStatusPayload(39_000, 70, false, 1u, StoreInstanceId(1uL))
        val duplicate = Pdu.response(
            opcode = Opcode.GET_STATUS_RSP,
            seq = 0,
            ackSeq = 0,
            payload = first.encode(),
        )
        val next = Pdu.response(
            opcode = Opcode.GET_STATUS_RSP,
            seq = 1,
            ackSeq = 1,
            payload = second.encode(),
        )
        backgroundScope.launch {
            transport.outbound.receive()
            transport.inject(duplicate)
            transport.outbound.receive()
            transport.inject(duplicate)
            transport.inject(next)
        }
        val one = session.sendAndAwait(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
            Opcode.GET_STATUS_RSP,
        )
        assertEquals(first, GetStatusPayload.decode(one.payload))
        val two = session.sendAndAwait(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
            Opcode.GET_STATUS_RSP,
        )
        assertEquals(second, GetStatusPayload.decode(two.payload))
    }

    @Test
    fun `the window treats a non-identical same-Seq inbound as a reject`() {
        val window = SequenceWindow()
        val original = byteArrayOf(1, 2, 3)
        window.markAccepted(5, original, response = byteArrayOf(9))
        assertEquals(SeqDecision.Reject, window.decide(5, byteArrayOf(1, 2, 4)))
        val replay = assertIs<SeqDecision.Retransmit>(window.decide(5, original))
        assertEquals(9, replay.cachedResponse[0])
    }
}
