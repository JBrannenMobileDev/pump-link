package dev.pumplink.protocol

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResponseBindingTest {

    private val pairingKey = ByteArray(32) { 3 }
    private val localId = LogicalDeviceId(ByteArray(16) { 4 })

    @Test
    fun `a late response for a different Seq is not taken as the answer`() = runTest {
        val transport = TestTransport()
        val session = Session(transport, pairingKey, localId)
        val stale = Pdu.response(
            opcode = Opcode.BOLUS_RSP,
            seq = 0,
            ackSeq = 99,
            commandId = CommandId(7u),
            payload = record(CommandId(7u), delivered = 700).encode(),
        )
        val expected = GetStatusPayload(50_000, 90, false, 0u, StoreInstanceId(1uL))
        val matching = Pdu.response(
            opcode = Opcode.GET_STATUS_RSP,
            seq = 1,
            ackSeq = 0,
            payload = expected.encode(),
        )
        backgroundScope.launch {
            transport.outbound.receive()
            transport.inject(stale)
            transport.inject(matching)
        }
        val rsp = session.sendAndAwait(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
            Opcode.GET_STATUS_RSP,
        )
        assertEquals(Opcode.GET_STATUS_RSP, rsp.opcode)
        assertEquals(expected, GetStatusPayload.decode(rsp.payload))
    }

    @Test
    fun `a BOLUS_RSP for command 7 is not taken as the answer to command 8`() = runTest {
        val transport = TestTransport()
        val session = Session(transport, pairingKey, localId)
        val forSeven = Pdu.response(
            opcode = Opcode.BOLUS_RSP,
            seq = 0,
            ackSeq = 0,
            commandId = CommandId(7u),
            payload = record(CommandId(7u), delivered = 700).encode(),
        )
        val forEight = Pdu.response(
            opcode = Opcode.BOLUS_RSP,
            seq = 1,
            ackSeq = 0,
            commandId = CommandId(8u),
            payload = record(CommandId(8u), delivered = 500).encode(),
        )
        backgroundScope.launch {
            transport.outbound.receive()
            transport.inject(forSeven)
            transport.inject(forEight)
        }
        val rsp = session.sendAndAwait(
            Pdu.request(
                Opcode.BOLUS_REQ,
                seq = session.nextSeq(),
                commandId = CommandId(8u),
                payload = BolusRequest(500, 60).encode(),
            ),
            Opcode.BOLUS_RSP,
        )
        val decoded = DeliveryRecord.decode(rsp.payload)
        assertEquals(CommandId(8u), decoded.commandId)
        assertEquals(500, decoded.deliveredMilliunits)
    }

    @Test
    fun `a NAK with ACK_SEQ_NONE is accepted`() = runTest {
        val transport = TestTransport()
        val session = Session(transport, pairingKey, localId)
        val nak = Pdu.response(
            opcode = Opcode.NAK,
            seq = 0,
            ackSeq = ProtocolLimits.ACK_SEQ_NONE,
            payload = byteArrayOf(NakReason.CRC_FAIL.code.toByte()),
        )
        backgroundScope.launch {
            transport.outbound.receive()
            transport.inject(nak)
        }
        val thrown = assertFailsWith<TransportException> {
            session.sendAndAwait(
                Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
                Opcode.GET_STATUS_RSP,
                attempts = 1,
            )
        }
        assertTrue(thrown.cause?.message?.contains("NAK") == true)
    }

    @Test
    fun `a NAK bound to a different Seq is discarded`() = runTest {
        val transport = TestTransport()
        val session = Session(transport, pairingKey, localId)
        val other = Pdu.response(
            opcode = Opcode.NAK,
            seq = 0,
            ackSeq = 40,
            payload = byteArrayOf(NakReason.BUSY.code.toByte()),
        )
        val expected = GetStatusPayload(10_000, 80, false, 1u, StoreInstanceId(2uL))
        val matching = Pdu.response(
            opcode = Opcode.GET_STATUS_RSP,
            seq = 1,
            ackSeq = 0,
            payload = expected.encode(),
        )
        backgroundScope.launch {
            transport.outbound.receive()
            transport.inject(other)
            transport.inject(matching)
        }
        val rsp = session.sendAndAwait(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
            Opcode.GET_STATUS_RSP,
        )
        assertEquals(expected, GetStatusPayload.decode(rsp.payload))
    }

    private fun record(commandId: CommandId, delivered: Int): DeliveryRecord = DeliveryRecord(
        commandId = commandId,
        state = RecordState.COMPLETED,
        requestedMilliunits = delivered,
        deliveredMilliunits = delivered,
        abortReason = 0,
        startedAt = 1u,
        endedAt = 2u,
    )
}
