package dev.pumplink.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

/**
 * One side of an L3/L4 session: fragmentation, sequence numbers, retry of a
 * byte-identical PDU, and the challenge/response handshake.
 */
class Session(
    private val transport: Transport,
    private val pairingKey: ByteArray,
    val localId: LogicalDeviceId,
    val expectedPeerId: LogicalDeviceId? = null,
) {
    private val outbound = SeqCounter()
    private val inbound = SequenceWindow()
    private val reassembler = Reassembler()
    private val random = SecureRandom()
    var sessionKey: ByteArray? = null
        private set
    var authenticated: Boolean = false
        private set

    suspend fun authenticateAsController() {
        reset()
        val nonceC = randomBytes(16)
        val challenge = sendAndAwait(
            Pdu.request(
                Opcode.AUTH_CHALLENGE_REQ,
                seq = outbound.take(),
                payload = localId.bytes + nonceC,
            ),
            expect = Opcode.AUTH_CHALLENGE_RSP,
        )
        require(challenge.payload.size == 32) { "AUTH_CHALLENGE_RSP payload" }
        val pumpId = LogicalDeviceId.fromBytes(challenge.payload, 0)
        expectedPeerId?.let { require(it == pumpId) { "pump identity mismatch" } }
        val nonceP = challenge.payload.copyOfRange(16, 32)
        val key = SessionCrypto.deriveSessionKey(pairingKey, nonceC, nonceP)
        sessionKey = key
        val verify = sendAndAwait(
            Pdu.request(
                Opcode.AUTH_VERIFY_REQ,
                seq = outbound.take(),
                payload = SessionCrypto.controllerVerifyMac(key, nonceC, nonceP),
            ),
            expect = Opcode.AUTH_VERIFY_RSP,
        )
        val expected = SessionCrypto.pumpVerifyMac(key, nonceC, nonceP)
        require(SessionCrypto.verifyAuthMac(expected, verify.payload)) { "pump verify MAC" }
        authenticated = true
    }

    /**
     * [attempts] is the number of send tries. The default is the initial send
     * plus [Timeouts.RETRY_ATTEMPTS] retries. A status poll uses 1.
     */
    suspend fun sendAndAwait(
        pdu: Pdu,
        expect: Opcode,
        timeoutMs: Long = Timeouts.T_RESP_MS,
        attempts: Int = Timeouts.RETRY_ATTEMPTS + 1,
    ): Pdu {
        val key = if (pdu.flags.auth || authenticated) sessionKey else null
        val toSend = if (authenticated) {
            pdu.copy(flags = pdu.flags.with(auth = true), mac = ByteArray(ProtocolLimits.MAC_SIZE))
        } else {
            pdu
        }
        val encoded = PduCodec.encode(toSend, key)
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(Timeouts.RETRY_BACKOFF_MS[attempt - 1])
            }
            try {
                return withTimeout(timeoutMs) {
                    sendEncoded(encoded)
                    awaitOpcode(expect, expectedAckSeq = toSend.seq, expectedCommandId = toSend.commandId)
                }
            } catch (thrown: Throwable) {
                lastError = thrown
            }
        }
        throw TransportException("no response to ${pdu.opcode} after retries", lastError)
    }

    suspend fun sendEncoded(encoded: ByteArray) {
        for (fragment in Fragmenter.fragment(encoded, transport.mtu)) {
            transport.send(fragment)
        }
    }

    suspend fun receivePdu(): Pdu {
        while (true) {
            val fragment = transport.receive()
            when (val result = reassembler.accept(fragment)) {
                is ReassemblyResult.Complete -> {
                    val decoded = PduCodec.decode(result.message, sessionKey)
                    when (decoded) {
                        is DecodeResult.Fail -> error("inbound ${decoded.reason}")
                        is DecodeResult.Ok -> {
                            when (decideInbound(decoded.pdu.seq, result.message)) {
                                SeqDecision.Reject,
                                is SeqDecision.Retransmit,
                                -> continue
                                SeqDecision.Accept -> {
                                    markInbound(decoded.pdu.seq, result.message, response = null)
                                    return decoded.pdu
                                }
                            }
                        }
                    }
                }
                ReassemblyResult.Incomplete -> Unit
                ReassemblyResult.Discarded -> error("reassembly error")
            }
        }
    }

    private suspend fun awaitOpcode(
        expect: Opcode,
        expectedAckSeq: Int,
        expectedCommandId: CommandId,
    ): Pdu {
        while (true) {
            val pdu = receivePdu()
            val usable = pdu.opcode != Opcode.BOLUS_PROGRESS_IND &&
                bindsTo(pdu, expectedAckSeq, expectedCommandId)
            if (usable && pdu.opcode == Opcode.NAK) {
                val reason = pdu.payload.firstOrNull()?.toInt()?.and(0xFF)
                    ?.let { NakReason.fromCode(it) }
                error("NAK $reason")
            }
            if (usable && pdu.opcode == expect) return pdu
        }
    }

    /**
     * A response answers this request when `AckSeq` matches the request `Seq`
     * and, where the request carried a CommandId, so does the response.
     * A decode-failure NAK cannot know the seq it is answering.
     */
    private fun bindsTo(pdu: Pdu, expectedAckSeq: Int, expectedCommandId: CommandId): Boolean {
        if (pdu.opcode == Opcode.NAK) {
            return pdu.ackSeq == expectedAckSeq || pdu.ackSeq == ProtocolLimits.ACK_SEQ_NONE
        }
        if (!pdu.flags.resp) return false
        if (pdu.ackSeq != expectedAckSeq) return false
        if (expectedCommandId != CommandId.NONE && pdu.commandId != expectedCommandId) return false
        return true
    }

    fun nextSeq(): Int = outbound.take()

    fun decideInbound(seq: Int, encoded: ByteArray): SeqDecision = inbound.decide(seq, encoded)

    fun markInbound(seq: Int, encoded: ByteArray, response: ByteArray?) {
        inbound.markAccepted(seq, encoded, response)
    }

    fun reset() {
        outbound.reset()
        inbound.reset()
        reassembler.reset()
        sessionKey = null
        authenticated = false
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
