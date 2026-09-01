package dev.pumplink.simulator

import dev.pumplink.protocol.BolusRequest
import dev.pumplink.protocol.CommandId
import dev.pumplink.protocol.CommandOutcome
import dev.pumplink.protocol.DecodeResult
import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.Opcode
import dev.pumplink.protocol.OutcomeInterpreter
import dev.pumplink.protocol.Pdu
import dev.pumplink.protocol.PduCodec
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.RecordState
import dev.pumplink.protocol.SessionCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PumpCoreTest {

    private val pairingKey = ByteArray(32) { 3 }
    private val identity = LogicalDeviceId(ByteArray(16) { 1 })
    private val controllerId = LogicalDeviceId(ByteArray(16) { 4 })

    @Test
    fun `I-1 no actuation without a committed record`() {
        val pump = PumpCore(identity, pairingKey)
        assertNull(pump.record(CommandId(1u)))
    }

    @Test
    fun `a new AUTH_CHALLENGE after traffic is accepted at Seq 0`() {
        val pump = PumpCore(identity, pairingKey)
        authenticate(pump)
        val key = authenticate(pump)
        val replies = pump.handleEncoded(bolus(key, CommandId(3u), 400, seq = 2))
        assertTrue(replies.isNotEmpty())
        assertEquals(400, pump.record(CommandId(3u))!!.requestedMilliunits)
    }

    @Test
    fun `I-2 a CommandId actuates at most once`() {
        val pump = PumpCore(identity, pairingKey)
        val key = authenticate(pump)
        val command = CommandId(7u)
        pump.handleEncoded(bolus(key, command, 1_000, seq = 2))
        val encoded = bolus(key, command, 1_000, seq = 2)
        pump.handleEncoded(encoded)
        pump.handleEncoded(encoded)
        val record = pump.record(command)!!
        assertEquals(1_000, record.deliveredMilliunits)
        assertTrue(record.deliveredMilliunits <= record.requestedMilliunits)
    }

    @Test
    fun `I-3 NEVER_SEEN implies zero actuation only with the same store`() {
        val pump = PumpCore(identity, pairingKey)
        val key = authenticate(pump)
        val command = CommandId(9u)
        val journaled = pump.storeInstanceId
        val before = pump.queryOutcome(command)
        assertEquals(CommandOutcome.NEVER_SEEN, before.outcome)
        assertEquals(CommandOutcome.NEVER_SEEN, OutcomeInterpreter.interpret(before, journaled))
        assertNull(pump.record(command))

        pump.handleEncoded(bolus(key, command, 500, seq = 2))
        assertTrue(pump.record(command)!!.deliveredMilliunits > 0)
        pump.resetStore()
        val after = pump.queryOutcome(command)
        assertEquals(CommandOutcome.NEVER_SEEN, after.outcome)
        assertEquals(CommandOutcome.STORE_REPLACED, OutcomeInterpreter.interpret(after, journaled))
        assertNotEquals(journaled, pump.storeInstanceId)
    }

    @Test
    fun `an over-limit request is aborted and not actuated`() {
        val pump = PumpCore(identity, pairingKey)
        val key = authenticate(pump)
        val command = CommandId(3u)
        pump.handleEncoded(bolus(key, command, 30_000, seq = 2))
        val record = pump.record(command)!!
        assertEquals(0, record.deliveredMilliunits)
        assertEquals(RecordState.ABORTED, record.state)
    }

    @Test
    fun `clearing disconnect-after-accept unblocks the next bolus`() {
        val pump = PumpCore(identity, pairingKey)
        val key = authenticate(pump)
        pump.fault = Fault.DISCONNECT_AFTER_ACCEPT
        pump.handleEncoded(bolus(key, CommandId(30u), 800, seq = 2))
        assertTrue(pump.deliveryActive)
        assertEquals(RecordState.IN_PROGRESS, pump.record(CommandId(30u))!!.state)
        pump.fault = Fault.NONE
        assertFalse(pump.deliveryActive)
        assertEquals(RecordState.COMPLETED, pump.record(CommandId(30u))!!.state)
        pump.handleEncoded(bolus(key, CommandId(31u), 500, seq = 3))
        val next = pump.record(CommandId(31u))!!
        assertEquals(RecordState.COMPLETED, next.state)
        assertEquals(500, next.deliveredMilliunits)
    }

    @Test
    fun `evictAll clears a stuck deliveryActive flag`() {
        val pump = PumpCore(identity, pairingKey)
        val key = authenticate(pump)
        pump.fault = Fault.DISCONNECT_AFTER_ACCEPT
        pump.handleEncoded(bolus(key, CommandId(32u), 800, seq = 2))
        pump.evictAll()
        assertFalse(pump.deliveryActive)
        pump.fault = Fault.NONE
        pump.handleEncoded(bolus(key, CommandId(33u), 500, seq = 3))
        assertEquals(500, pump.record(CommandId(33u))!!.deliveredMilliunits)
    }

    @Test
    fun `eviction reports EVICTED for identifiers below the watermark`() {
        val pump = PumpCore(identity, pairingKey)
        for (n in 1..ProtocolLimits.MIN_RETAINED_RECORDS + 1) {
            pump.seed(CommandId(n.toUInt()))
        }
        assertEquals(CommandOutcome.EVICTED, pump.queryOutcome(CommandId(1u)).outcome)
    }

    private fun authenticate(pump: PumpCore): ByteArray {
        val nonceC = ByteArray(16) { 9 }
        val challenge = Pdu.request(
            Opcode.AUTH_CHALLENGE_REQ,
            seq = 0,
            payload = controllerId.bytes + nonceC,
        )
        val replies = pump.handleEncoded(PduCodec.encode(challenge))
        val rsp = (PduCodec.decode(replies.first()) as DecodeResult.Ok).pdu
        val nonceP = rsp.payload.copyOfRange(16, 32)
        val key = SessionCrypto.deriveSessionKey(pairingKey, nonceC, nonceP)
        val verify = Pdu.request(
            Opcode.AUTH_VERIFY_REQ,
            seq = 1,
            payload = SessionCrypto.controllerVerifyMac(key, nonceC, nonceP),
        )
        pump.handleEncoded(PduCodec.encode(verify))
        require(pump.authenticated)
        return key
    }

    private fun bolus(key: ByteArray, commandId: CommandId, milliunits: Int, seq: Int): ByteArray =
        PduCodec.encode(
            Pdu.request(
                Opcode.BOLUS_REQ,
                seq = seq,
                commandId = commandId,
                payload = BolusRequest(milliunits, 60).encode(),
                auth = true,
            ),
            key,
        )
}
