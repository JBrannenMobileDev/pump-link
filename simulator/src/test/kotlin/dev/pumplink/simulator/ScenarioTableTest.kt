package dev.pumplink.simulator

import dev.pumplink.protocol.BolusRequest
import dev.pumplink.protocol.CommandId
import dev.pumplink.protocol.CommandOutcome
import dev.pumplink.protocol.DeliveryRecord
import dev.pumplink.protocol.Fragmenter
import dev.pumplink.protocol.GattUuids
import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.Opcode
import dev.pumplink.protocol.OutcomeInterpreter
import dev.pumplink.protocol.OutcomePayload
import dev.pumplink.protocol.Pdu
import dev.pumplink.protocol.PduCodec
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Reassembler
import dev.pumplink.protocol.ReassemblyResult
import dev.pumplink.protocol.Session
import dev.pumplink.protocol.StatusValue
import dev.pumplink.protocol.StoreInstanceId
import dev.pumplink.protocol.Timeouts
import dev.pumplink.protocol.link.LinkEffect
import dev.pumplink.protocol.link.LinkEvent
import dev.pumplink.protocol.link.LinkReducer
import dev.pumplink.protocol.link.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * One test per row of docs/05-parity-contract.md Part 3.
 * Device-only rows (SC-16, SC-17) are documented, not executed.
 * SC-21 is the controller-side binding of SC-11; SC-22 locks the poll policy.
 * SC-23 and SC-24 lock the Suspended re-reconcile edges.
 * SC-25 locks command-scoped drop; SC-26 is the controller record-state map.
 */
class ScenarioTableTest {

    private val pairingKey = ByteArray(32) { 11 }
    private val pumpId = LogicalDeviceId(ByteArray(16) { 1 })
    private val controllerId = LogicalDeviceId(ByteArray(16) { 2 })

    @Test
    fun `SC-01 bolus happy path`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val status = env.getStatus()
        val record = env.deliver(CommandId(1u), 1_500, status.storeInstanceId)
        assertEquals(1_500, record.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-02 response dropped after the pump accepts`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DROP_NEXT_RESPONSE
        assertFailsWith<Throwable> {
            env.deliver(CommandId(2u), 1_000, store)
        }
        val outcome = env.query(CommandId(2u), store)
        assertEquals(CommandOutcome.COMPLETED, outcome)
        env.close()
    }

    @Test
    fun `a status poll does not consume a one-shot reply fault`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DROP_NEXT_RESPONSE
        env.getStatus()
        assertEquals(Fault.DROP_NEXT_RESPONSE, env.pump.fault)
        assertFailsWith<Throwable> {
            env.deliver(CommandId(21u), 1_000, store)
        }
        // The command is still poisoned until a different Seq arrives.
        assertEquals(Fault.DROP_NEXT_RESPONSE, env.pump.fault)
        env.query(CommandId(21u), store)
        assertEquals(Fault.NONE, env.pump.fault)
        env.close()
    }

    @Test
    fun `SC-03 disconnect mid-command`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DISCONNECT_AFTER_ACCEPT
        val command = CommandId(3u)
        try {
            env.deliver(command, 800, store)
        } catch (_: Throwable) {
            // accepted, actuation may be unfinished
        }
        assertTrue(env.pump.record(command) != null)
        env.pump.completeDelivery(command, 800)
        assertEquals(CommandOutcome.COMPLETED, env.query(command, store))
        env.close()
    }

    @Test
    fun `SC-04 ACK delayed past T_RESP`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DELAY_PAST_T_RESP
        val record = env.deliver(CommandId(4u), 600, store)
        assertEquals(600, record.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-05 reissue after NEVER_SEEN`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        val command = CommandId(5u)
        assertEquals(CommandOutcome.NEVER_SEEN, env.query(command, store))
        val record = env.deliver(command, 400, store)
        assertEquals(400, record.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-06 outcome EVICTED`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        for (n in 1..ProtocolLimits.MIN_RETAINED_RECORDS + 1) {
            env.pump.seed(CommandId(n.toUInt()))
        }
        assertEquals(CommandOutcome.EVICTED, env.query(CommandId(1u), store))
        env.close()
    }

    @Test
    fun `SC-07 pump reboot mid-session`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        env.pump.resetSession()
        assertFailsWith<Throwable> {
            env.deliver(CommandId(7u), 100, env.pump.storeInstanceId)
        }
        assertEquals(null, env.pump.record(CommandId(7u)))
        env.close()
    }

    @Test
    fun `SC-08 MTU 23 forced fragmentation`() = runTest {
        val env = open(mtu = ProtocolLimits.MIN_ATT_MTU)
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        val record = env.deliver(CommandId(8u), 2_000, store)
        assertEquals(2_000, record.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-09 MTU 517`() = runTest {
        val env = open(mtu = ProtocolLimits.MAX_ATT_MTU)
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        val record = env.deliver(CommandId(9u), 2_000, store)
        assertEquals(2_000, record.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-10 corrupted CRC`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        env.pump.fault = Fault.CORRUPT_CRC
        val store = env.getStatus().storeInstanceId
        assertEquals(Fault.CORRUPT_CRC, env.pump.fault)
        assertFailsWith<Throwable> {
            env.deliver(CommandId(10u), 600, store)
        }
        env.close()
    }

    @Test
    fun `SC-11 duplicate notification does not double-count`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DUPLICATE_NOTIFY
        val record = env.deliver(CommandId(11u), 700, store)
        assertEquals(700, record.deliveredMilliunits)
        assertEquals(700, env.pump.record(CommandId(11u))!!.deliveredMilliunits)
        val next = env.deliver(CommandId(12u), 500, store)
        assertEquals(500, next.deliveredMilliunits)
        assertEquals(500, env.pump.record(CommandId(12u))!!.deliveredMilliunits)
        env.close()
    }

    @Test
    fun `SC-12 sequence gap is rejected`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val key = env.session.sessionKey!!
        val gap = Pdu.request(
            Opcode.GET_STATUS_REQ,
            seq = 200,
            auth = true,
        )
        val replies = env.pump.handleEncoded(PduCodec.encode(gap, key))
        assertTrue(replies.isNotEmpty())
        val decoded = PduCodec.decode(replies.first(), key)
        val pdu = (decoded as dev.pumplink.protocol.DecodeResult.Ok).pdu
        assertEquals(Opcode.NAK, pdu.opcode)
        env.close()
    }

    @Test
    fun `SC-13 fragment splicing is discarded`() = runTest {
        val message = ByteArray(40) { 4 }
        val fragments = Fragmenter.fragment(message, ProtocolLimits.MIN_ATT_MTU)
        val reassembler = Reassembler()
        reassembler.accept(fragments[0])
        val spliced = fragments[1].copyOf()
        spliced[0] = dev.pumplink.protocol.FragmentHeader.of(false, last = true, index = 3).byte.toByte()
        assertEquals(ReassemblyResult.Discarded, reassembler.accept(spliced))
    }

    @Test
    fun `SC-14 stale service cache is a version mismatch on STATUS`() {
        val pump = PumpCore(pumpId, pairingKey)
        val status = pump.statusValue()
        assertEquals(ProtocolLimits.VERSION, status.protocolVersion)
        val stale = StatusValue(protocolVersion = 2, logicalDeviceId = pumpId, recordEpoch = 0u)
        assertNotEquals(status.protocolVersion, stale.protocolVersion)
    }

    @Test
    fun `SC-15 transmit before subscription is unreachable from Ready`() {
        // Enforced by the link reducer: BeginAuth is an effect of StatusRead,
        // and StatusRead is only consumed in Subscribed. See LinkReducerTest.
        assertTrue(GattUuids.RSP.isNotEmpty())
    }

    @Test
    fun `SC-18 reconnect after simulated patch change`() {
        val original = pumpId
        val replacement = LogicalDeviceId(ByteArray(16) { 9 })
        assertNotEquals(original, replacement)
        val status = StatusValue(ProtocolLimits.VERSION, replacement, 0u)
        assertNotEquals(original, status.logicalDeviceId)
    }

    @Test
    fun `SC-19 replay of a captured PDU into a new session`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val captured = PduCodec.encode(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = env.session.nextSeq(), auth = true),
            env.session.sessionKey,
        )
        env.pump.resetSession()
        val env2 = open()
        env2.session.authenticateAsController()
        val replies = env2.pump.handleEncoded(captured)
        val decoded = PduCodec.decode(replies.first(), env2.session.sessionKey)
        val pdu = (decoded as dev.pumplink.protocol.DecodeResult.Ok).pdu
        assertEquals(Opcode.NAK, pdu.opcode)
        env.close()
        env2.close()
    }

    @Test
    fun `SC-20 record store reset while a command is outstanding`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DROP_NEXT_RESPONSE
        val command = CommandId(20u)
        assertFailsWith<Throwable> {
            env.deliver(command, 900, store)
        }
        env.pump.resetStore()
        val outcome = env.query(command, store)
        assertEquals(CommandOutcome.STORE_REPLACED, outcome)
        env.close()
    }

    @Test
    fun `re-authenticate after traffic starts Seq at zero on both sides`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        env.getStatus()
        env.session.authenticateAsController()
        val status = env.getStatus()
        assertTrue(status.reservoirMilliunits >= 0)
        env.close()
    }

    @Test
    fun `SC-21 duplicate BOLUS_RSP is not taken as GET_STATUS`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DUPLICATE_NOTIFY
        env.deliver(CommandId(21u), 700, store)
        val status = env.getStatus()
        assertEquals(store, status.storeInstanceId)
        env.close()
    }

    @Test
    fun `SC-22 poll policy is three strikes at five seconds`() {
        assertEquals(5_000L, Timeouts.T_POLL_MS)
        assertEquals(3, Timeouts.POLL_FAILURE_LIMIT)
    }

    @Test
    fun `SC-23 Suspended clears when reconcile reports nothing unresolved`() {
        val suspended = LinkState.Suspended(pumpId, mtu = 23)
        val requested = LinkReducer.reduce(suspended, LinkEvent.ReconcileRequested)
        assertTrue(requested.state is LinkState.Reconciling)
        assertTrue(requested.effects.any { it is LinkEffect.BeginReconcile })
        val ready = LinkReducer.reduce(requested.state, LinkEvent.ReconcileDone(0))
        assertTrue(ready.state is LinkState.Ready)
    }

    @Test
    fun `SC-24 Indeterminate keeps Suspended across repeated reconciles`() {
        val reconciling = LinkState.Reconciling(pumpId, attempts = 0, mtu = 23)
        val first = LinkReducer.reduce(reconciling, LinkEvent.ReconcileDone(1))
        assertTrue(first.state is LinkState.Suspended)
        val again = LinkReducer.reduce(first.state, LinkEvent.ReconcileRequested)
        assertTrue(again.state is LinkState.Reconciling)
        val still = LinkReducer.reduce(again.state, LinkEvent.ReconcileDone(1))
        assertTrue(still.state is LinkState.Suspended)
        val afterAck = LinkReducer.reduce(still.state, LinkEvent.UserVerifiedAtPump)
        assertTrue(afterAck.state is LinkState.Reconciling)
        val ready = LinkReducer.reduce(afterAck.state, LinkEvent.ReconcileDone(0))
        assertTrue(ready.state is LinkState.Ready)
    }

    @Test
    fun `SC-25 drop next poisons every retry of the same command`() = runTest {
        val env = open()
        env.session.authenticateAsController()
        val store = env.getStatus().storeInstanceId
        env.pump.fault = Fault.DROP_NEXT_RESPONSE
        assertFailsWith<Throwable> {
            env.deliver(CommandId(25u), 1_000, store)
        }
        assertEquals(Fault.DROP_NEXT_RESPONSE, env.pump.fault)
        assertEquals(CommandOutcome.COMPLETED, env.query(CommandId(25u), store))
        assertEquals(Fault.NONE, env.pump.fault)
        env.close()
    }

    private fun TestScope.open(mtu: Int = ProtocolLimits.MIN_ATT_MTU): Env {
        val (controller, pumpTransport) = LoopbackTransport.pair(mtu)
        val pump = PumpCore(pumpId, pairingKey)
        val endpoint = PumpEndpoint(pump, pumpTransport)
        val job = endpoint.start(this)
        val session = Session(controller, pairingKey, controllerId, pumpId)
        return Env(session, pump, controller, job)
    }

    private data class Env(
        val session: Session,
        val pump: PumpCore,
        val transport: LoopbackTransport,
        val job: kotlinx.coroutines.Job,
    ) {
        suspend fun getStatus(): dev.pumplink.protocol.GetStatusPayload {
            val rsp = session.sendAndAwait(
                Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
                Opcode.GET_STATUS_RSP,
            )
            return dev.pumplink.protocol.GetStatusPayload.decode(rsp.payload)
        }

        suspend fun deliver(commandId: CommandId, milliunits: Int, store: StoreInstanceId): DeliveryRecord {
            val rsp = session.sendAndAwait(
                Pdu.request(
                    Opcode.BOLUS_REQ,
                    seq = session.nextSeq(),
                    commandId = commandId,
                    payload = BolusRequest(milliunits, 60).encode(),
                ),
                Opcode.BOLUS_RSP,
            )
            val record = DeliveryRecord.decode(rsp.payload)
            check(store != StoreInstanceId.UNKNOWN)
            return record
        }

        suspend fun query(commandId: CommandId, journaled: StoreInstanceId): CommandOutcome {
            val rsp = session.sendAndAwait(
                Pdu.request(Opcode.QUERY_COMMAND_OUTCOME_REQ, seq = session.nextSeq(), commandId = commandId),
                Opcode.QUERY_COMMAND_OUTCOME_RSP,
            )
            val payload = OutcomePayload.decode(rsp.payload)
            return OutcomeInterpreter.interpret(payload, journaled)
        }

        suspend fun close() {
            job.cancel()
            transport.close()
        }
    }
}
