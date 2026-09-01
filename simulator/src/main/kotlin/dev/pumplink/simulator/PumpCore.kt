package dev.pumplink.simulator

import dev.pumplink.protocol.AbortCodes
import dev.pumplink.protocol.BolusRequest
import dev.pumplink.protocol.CommandId
import dev.pumplink.protocol.CommandOutcome
import dev.pumplink.protocol.DecodeResult
import dev.pumplink.protocol.DeliveryRecord
import dev.pumplink.protocol.Flags
import dev.pumplink.protocol.GetStatusPayload
import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.NakReason
import dev.pumplink.protocol.Opcode
import dev.pumplink.protocol.OutcomePayload
import dev.pumplink.protocol.Pdu
import dev.pumplink.protocol.PduCodec
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.RecordState
import dev.pumplink.protocol.SeqCounter
import dev.pumplink.protocol.SeqDecision
import dev.pumplink.protocol.SequenceWindow
import dev.pumplink.protocol.SessionCrypto
import dev.pumplink.protocol.StatusValue
import dev.pumplink.protocol.StoreInstanceId
import java.security.SecureRandom

/**
 * Pump decision logic with no radio. The record store, outcome query, and
 * safety limits live here so the scenario table can pin them down on the JVM.
 */
class PumpCore(
    val identity: LogicalDeviceId,
    val pairingKey: ByteArray,
    val limits: SafetyLimits = SafetyLimits(),
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> UInt = { ticks++ },
) {
    private val records = LinkedHashMap<CommandId, DeliveryRecord>()
    private val inbound = SequenceWindow()
    private val outbound = SeqCounter()
    private var nonceC: ByteArray? = null
    private var nonceP: ByteArray? = null
    var sessionKey: ByteArray? = null
        private set
    var authenticated: Boolean = false
        private set

    var storeInstanceId: StoreInstanceId = newStoreId()
        private set
    var recordEpoch: UInt = 0u
        private set
    var reservoirMilliunits: Int = limits.defaultReservoirMilliunits
    var batteryPercent: Int = 90
    var deliveryActive: Boolean = false
        private set

    var fault: Fault = Fault.NONE
        set(value) {
            field = value
            // Disconnect-after-accept leaves an IN_PROGRESS record and
            // deliveryActive set. Once that fault is no longer armed, finish
            // those records so the next bolus is not rejected at 0 U.
            if (value != Fault.DISCONNECT_AFTER_ACCEPT) {
                settleStuckDeliveries()
            }
        }
    private var poisonedSeq: Int? = null

    val retainedCount: Int get() = records.size
    val oldestRetainedCommandId: CommandId
        get() = records.keys.firstOrNull() ?: CommandId.NONE

    fun statusValue(): StatusValue = StatusValue(
        protocolVersion = ProtocolLimits.VERSION,
        logicalDeviceId = identity,
        recordEpoch = recordEpoch,
    )

    fun getStatusPayload(): GetStatusPayload = GetStatusPayload(
        reservoirMilliunits = reservoirMilliunits,
        batteryPercent = batteryPercent,
        deliveryActive = deliveryActive,
        recordEpoch = recordEpoch,
        storeInstanceId = storeInstanceId,
    )

    /**
     * Accept a reassembled L2 PDU and return zero or more encoded responses.
     * Returns empty when a fault drops the reply.
     */
    fun handleEncoded(encoded: ByteArray): List<ByteArray> {
        val decoded = PduCodec.decode(encoded, sessionKey)
        val pdu = when (decoded) {
            is dev.pumplink.protocol.DecodeResult.Ok -> decoded.pdu
            is dev.pumplink.protocol.DecodeResult.Fail -> {
                return listOf(encodeNak(decoded.reason, seq = 0, ackSeq = ProtocolLimits.ACK_SEQ_NONE))
            }
        }
        // A new challenge is a new session. Seq starts at 0 on both sides;
        // leaving the previous window in place NAKs a legitimate reconnect.
        // A byte-identical retry of the current challenge is a retransmission
        // and must keep the cached response — reset only when the window
        // would otherwise reject it.
        if (pdu.opcode == Opcode.AUTH_CHALLENGE_REQ &&
            inbound.decide(pdu.seq, encoded) == SeqDecision.Reject
        ) {
            resetSession()
        }
        return when (val decision = inbound.decide(pdu.seq, encoded)) {
            SeqDecision.Reject -> listOf(encodeNak(NakReason.SEQ_OUT_OF_WINDOW, outbound.take(), pdu.seq))
            is SeqDecision.Retransmit -> applyFault(listOf(decision.cachedResponse), pdu.opcode, pdu.seq)
            SeqDecision.Accept -> {
                val replies = handleAccepted(pdu)
                val first = replies.firstOrNull()
                inbound.markAccepted(pdu.seq, encoded, first)
                applyFault(replies, pdu.opcode, pdu.seq)
            }
        }
    }

    fun queryOutcome(commandId: CommandId): OutcomePayload {
        val record = records[commandId]
        if (record != null) {
            val outcome = when (record.state) {
                RecordState.ACCEPTED -> CommandOutcome.ACCEPTED
                RecordState.IN_PROGRESS -> CommandOutcome.IN_PROGRESS
                RecordState.COMPLETED -> CommandOutcome.COMPLETED
                RecordState.ABORTED -> CommandOutcome.ABORTED
            }
            return OutcomePayload(outcome, record, oldestRetainedCommandId, storeInstanceId)
        }
        val oldest = oldestRetainedCommandId
        val evicted = oldest != CommandId.NONE && commandId < oldest
        val outcome = if (evicted) CommandOutcome.EVICTED else CommandOutcome.NEVER_SEEN
        return OutcomePayload(outcome, DeliveryRecord.EMPTY, oldest, storeInstanceId)
    }

    fun resetStore() {
        records.clear()
        storeInstanceId = newStoreId()
        recordEpoch += 1u
        deliveryActive = false
    }

    fun evictAll() {
        records.clear()
        recordEpoch += 1u
        deliveryActive = false
    }

    /**
     * Completes any accepted/in-progress records and clears [deliveryActive].
     * The only simulator path that leaves those hanging is
     * [Fault.DISCONNECT_AFTER_ACCEPT].
     */
    fun settleStuckDeliveries() {
        val stuck = records.values.filter {
            it.state == RecordState.IN_PROGRESS || it.state == RecordState.ACCEPTED
        }
        for (record in stuck) {
            completeDelivery(record.commandId, record.requestedMilliunits)
        }
        deliveryActive = false
    }

    /** Test/harness helper: insert a completed record without a session. */
    fun seed(commandId: CommandId, milliunits: Int = 50) {
        commit(
            DeliveryRecord(
                commandId = commandId,
                state = RecordState.COMPLETED,
                requestedMilliunits = milliunits,
                deliveredMilliunits = milliunits,
                abortReason = 0,
                startedAt = clock(),
                endedAt = clock(),
            ),
        )
    }

    fun completeDelivery(commandId: CommandId, deliveredMilliunits: Int) {
        val current = records[commandId] ?: return
        records[commandId] = current.copy(
            state = RecordState.COMPLETED,
            deliveredMilliunits = deliveredMilliunits,
            endedAt = clock(),
        )
        reservoirMilliunits = (reservoirMilliunits - deliveredMilliunits).coerceAtLeast(0)
        deliveryActive = false
        recordEpoch += 1u
    }

    fun abortDelivery(commandId: CommandId, deliveredMilliunits: Int, reason: Int) {
        val current = records[commandId] ?: return
        records[commandId] = current.copy(
            state = RecordState.ABORTED,
            deliveredMilliunits = deliveredMilliunits,
            abortReason = reason,
            endedAt = clock(),
        )
        reservoirMilliunits = (reservoirMilliunits - deliveredMilliunits).coerceAtLeast(0)
        deliveryActive = false
        recordEpoch += 1u
    }

    fun record(commandId: CommandId): DeliveryRecord? = records[commandId]

    fun resetSession() {
        inbound.reset()
        outbound.reset()
        sessionKey = null
        authenticated = false
        nonceC = null
        nonceP = null
        poisonedSeq = null
    }

    fun isStatusRequest(encoded: ByteArray): Boolean {
        val decoded = PduCodec.decode(encoded, sessionKey)
        return decoded is DecodeResult.Ok && decoded.pdu.opcode == Opcode.GET_STATUS_REQ
    }

    private fun handleAccepted(pdu: Pdu): List<ByteArray> {
        return when (pdu.opcode) {
            Opcode.AUTH_CHALLENGE_REQ -> listOf(handleAuthChallenge(pdu))
            Opcode.AUTH_VERIFY_REQ -> listOf(handleAuthVerify(pdu))
            Opcode.SESSION_END -> {
                resetSession()
                listOf(encode(Opcode.ACK, pdu.seq, pdu.commandId, ByteArray(0), auth = false))
            }
            Opcode.GET_STATUS_REQ -> operational(pdu) {
                encode(Opcode.GET_STATUS_RSP, pdu.seq, pdu.commandId, getStatusPayload().encode())
            }
            Opcode.BOLUS_REQ -> operational(pdu) { handleBolus(pdu) }
            Opcode.BOLUS_CANCEL_REQ -> operational(pdu) { handleCancel(pdu) }
            Opcode.QUERY_COMMAND_OUTCOME_REQ -> operational(pdu) { handleQuery(pdu) }
            Opcode.GET_HISTORY_REQ -> operational(pdu) { handleHistory(pdu) }
            Opcode.ACK -> emptyList()
            Opcode.AUTH_CHALLENGE_RSP,
            Opcode.AUTH_VERIFY_RSP,
            Opcode.GET_STATUS_RSP,
            Opcode.BOLUS_RSP,
            Opcode.BOLUS_CANCEL_RSP,
            Opcode.BOLUS_PROGRESS_IND,
            Opcode.QUERY_COMMAND_OUTCOME_RSP,
            Opcode.GET_HISTORY_RSP,
            Opcode.NAK,
            -> listOf(encodeNak(NakReason.UNKNOWN_OPCODE, outbound.take(), pdu.seq))
        }
    }

    private fun operational(pdu: Pdu, body: () -> ByteArray): List<ByteArray> {
        if (!authenticated) {
            return listOf(encodeNak(NakReason.NOT_AUTHENTICATED, outbound.take(), pdu.seq))
        }
        return listOf(body())
    }

    private fun handleAuthChallenge(pdu: Pdu): ByteArray {
        require(pdu.payload.size == 32) { "AUTH_CHALLENGE_REQ" }
        val incomingNonce = pdu.payload.copyOfRange(16, 32)
        val generatedNonce = ByteArray(16).also { random.nextBytes(it) }
        nonceC = incomingNonce
        nonceP = generatedNonce
        sessionKey = SessionCrypto.deriveSessionKey(pairingKey, incomingNonce, generatedNonce)
        authenticated = false
        val payload = identity.bytes + generatedNonce
        return encode(Opcode.AUTH_CHALLENGE_RSP, pdu.seq, CommandId.NONE, payload, auth = false)
    }

    private fun handleAuthVerify(pdu: Pdu): ByteArray {
        val key = sessionKey
        val c = nonceC
        val p = nonceP
        if (key == null || c == null || p == null) {
            return encodeNak(NakReason.NOT_AUTHENTICATED, outbound.take(), pdu.seq)
        }
        val expected = SessionCrypto.controllerVerifyMac(key, c, p)
        if (!SessionCrypto.verifyAuthMac(expected, pdu.payload)) {
            resetSession()
            return encodeNak(NakReason.BAD_MAC, outbound.take(), pdu.seq)
        }
        authenticated = true
        val payload = SessionCrypto.pumpVerifyMac(key, c, p)
        return encode(Opcode.AUTH_VERIFY_RSP, pdu.seq, CommandId.NONE, payload, auth = false)
    }

    private fun handleBolus(pdu: Pdu): ByteArray {
        if (fault != Fault.DISCONNECT_AFTER_ACCEPT) {
            settleStuckDeliveries()
        }
        val existing = records[pdu.commandId]
        if (existing != null) {
            return encode(Opcode.BOLUS_RSP, pdu.seq, pdu.commandId, existing.encode())
        }
        if (pdu.payload.size < BolusRequest.SIZE) {
            return encodeNak(NakReason.MALFORMED_PAYLOAD, outbound.take(), pdu.seq)
        }
        val request = BolusRequest.decode(pdu.payload)
        val now = clock()
        val rejected = validate(request)
        val record = if (rejected != null) {
            DeliveryRecord(
                commandId = pdu.commandId,
                state = RecordState.ABORTED,
                requestedMilliunits = request.requestedMilliunits,
                deliveredMilliunits = 0,
                abortReason = rejected,
                startedAt = now,
                endedAt = now,
            )
        } else {
            DeliveryRecord(
                commandId = pdu.commandId,
                state = RecordState.ACCEPTED,
                requestedMilliunits = request.requestedMilliunits,
                deliveredMilliunits = 0,
                abortReason = 0,
                startedAt = now,
                endedAt = 0u,
            )
        }
        commit(record)
        if (record.state == RecordState.ACCEPTED) {
            val inProgress = record.copy(state = RecordState.IN_PROGRESS)
            records[pdu.commandId] = inProgress
            deliveryActive = true
            recordEpoch += 1u
            if (fault != Fault.DISCONNECT_AFTER_ACCEPT) {
                completeDelivery(pdu.commandId, record.requestedMilliunits)
            }
        }
        return encode(Opcode.BOLUS_RSP, pdu.seq, pdu.commandId, records.getValue(pdu.commandId).encode())
    }

    private fun handleCancel(pdu: Pdu): ByteArray {
        val current = records[pdu.commandId]
            ?: return encode(Opcode.BOLUS_CANCEL_RSP, pdu.seq, pdu.commandId, DeliveryRecord.EMPTY.encode())
        if (current.state == RecordState.IN_PROGRESS || current.state == RecordState.ACCEPTED) {
            abortDelivery(pdu.commandId, current.deliveredMilliunits, AbortCodes.USER_CANCELLED)
        }
        return encode(Opcode.BOLUS_CANCEL_RSP, pdu.seq, pdu.commandId, records.getValue(pdu.commandId).encode())
    }

    private fun handleQuery(pdu: Pdu): ByteArray {
        return encode(
            Opcode.QUERY_COMMAND_OUTCOME_RSP,
            pdu.seq,
            pdu.commandId,
            queryOutcome(pdu.commandId).encode(),
        )
    }

    private fun handleHistory(pdu: Pdu): ByteArray {
        val since = if (pdu.payload.size >= 4) CommandId.fromBytes(pdu.payload, 0) else CommandId.NONE
        val selected = records.values.filter { it.commandId > since }.take(255)
        val out = ArrayList<Byte>(1 + selected.size * ProtocolLimits.DELIVERY_RECORD_SIZE)
        out.add(selected.size.toByte())
        selected.forEach { record -> out.addAll(record.encode().toList()) }
        return encode(Opcode.GET_HISTORY_RSP, pdu.seq, pdu.commandId, out.toByteArray())
    }

    private fun validate(request: BolusRequest): Int? {
        if (request.requestedMilliunits <= 0) return AbortCodes.INVALID_DOSE
        if (request.requestedMilliunits > limits.maxBolusMilliunits) return AbortCodes.EXCEEDS_MAX
        if (request.requestedMilliunits % limits.incrementMilliunits != 0) return AbortCodes.BAD_INCREMENT
        if (request.requestedMilliunits > reservoirMilliunits) return AbortCodes.INSUFFICIENT_RESERVOIR
        if (request.maxDurationSeconds > limits.maxDurationSeconds) return AbortCodes.EXCEEDS_DURATION
        if (deliveryActive) return AbortCodes.DELIVERY_ACTIVE
        return null
    }

    private fun commit(record: DeliveryRecord) {
        records[record.commandId] = record
        while (records.size > ProtocolLimits.MIN_RETAINED_RECORDS) {
            val oldest = records.keys.first()
            records.remove(oldest)
        }
        recordEpoch += 1u
    }

    /**
     * Reply-shaping faults skip [Opcode.GET_STATUS_REQ]. The live controller
     * polls status every few seconds; consuming a one-shot injector there
     * makes "Drop next RSP" then Deliver impossible. The skip also keeps a
     * poisoned command armed: a status poll uses a different Seq and must
     * not clear [poisonedSeq].
     *
     * [Fault.DROP_NEXT_RESPONSE] and [Fault.CORRUPT_CRC] poison the request
     * Seq so every retransmission of that command is shaped. A later request
     * with a different Seq (the follow-up query) clears the poison.
     */
    private fun applyFault(replies: List<ByteArray>, request: Opcode, seq: Int): List<ByteArray> {
        if (request == Opcode.GET_STATUS_REQ && fault.shapesReplies()) {
            return replies
        }
        releasePoisonIfMovedOn(seq)
        return when (fault) {
            Fault.NONE, Fault.DISCONNECT_AFTER_ACCEPT -> replies
            Fault.DROP_NEXT_RESPONSE -> {
                poison(seq)
                emptyList()
            }
            Fault.DROP_ALL_RESPONSES -> emptyList()
            Fault.CORRUPT_CRC -> {
                poison(seq)
                replies.map { bytes ->
                    if (bytes.size < 2) bytes else bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
                }
            }
            Fault.DELAY_PAST_T_RESP -> replies
            Fault.DUPLICATE_NOTIFY -> {
                fault = Fault.NONE
                replies + replies
            }
            Fault.EVICT_ALL -> {
                evictAll()
                fault = Fault.NONE
                replies
            }
            Fault.RESET_STORE -> {
                resetStore()
                fault = Fault.NONE
                replies
            }
        }
    }

    private fun poison(seq: Int) {
        if (poisonedSeq == null) poisonedSeq = seq
    }

    private fun releasePoisonIfMovedOn(seq: Int) {
        val poison = poisonedSeq ?: return
        if (poison != seq) {
            poisonedSeq = null
            if (fault == Fault.DROP_NEXT_RESPONSE || fault == Fault.CORRUPT_CRC) {
                fault = Fault.NONE
            }
        }
    }

    private fun encode(
        opcode: Opcode,
        ackSeq: Int,
        commandId: CommandId,
        payload: ByteArray,
        auth: Boolean = authenticated,
    ): ByteArray {
        val pdu = Pdu.response(
            opcode = opcode,
            seq = outbound.take(),
            ackSeq = ackSeq,
            commandId = commandId,
            payload = payload,
            auth = auth,
        )
        return PduCodec.encode(pdu, if (auth) sessionKey else null)
    }

    private fun encodeNak(reason: NakReason, seq: Int, ackSeq: Int): ByteArray {
        val pdu = Pdu(
            flags = Flags.of(auth = authenticated, resp = true),
            opcode = Opcode.NAK,
            seq = seq,
            ackSeq = ackSeq,
            commandId = CommandId.NONE,
            payload = byteArrayOf(reason.code.toByte()),
        )
        return PduCodec.encode(pdu, if (authenticated) sessionKey else null)
    }

    private fun newStoreId(): StoreInstanceId {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return StoreInstanceId.fromBytes(bytes)
    }

    companion object {
        private var ticks: UInt = 1u
    }
}
