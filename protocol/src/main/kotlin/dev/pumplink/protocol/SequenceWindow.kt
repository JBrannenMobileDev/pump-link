package dev.pumplink.protocol

sealed interface SeqDecision {
    data object Accept : SeqDecision
    data class Retransmit(val cachedResponse: ByteArray) : SeqDecision
    data object Reject : SeqDecision
}

/**
 * Last-accepted starts at 255 so the first Seq of 0 is inside 1..127.
 * A byte-identical repeat of lastAccepted is a retransmission.
 */
class SequenceWindow {
    private var lastAccepted: Int = 255
    private var lastAcceptedBytes: ByteArray? = null
    private var lastResponse: ByteArray? = null
    private var opened: Boolean = false

    fun decide(seq: Int, encodedPdu: ByteArray): SeqDecision {
        require(seq in 0..255)
        if (opened && seq == lastAccepted && lastAcceptedBytes.contentEquals(encodedPdu)) {
            val cached = lastResponse
            return if (cached != null) SeqDecision.Retransmit(cached) else SeqDecision.Accept
        }
        val delta = (seq - lastAccepted + ProtocolLimits.SEQ_MODULUS) % ProtocolLimits.SEQ_MODULUS
        return if (delta in 1..127) {
            SeqDecision.Accept
        } else {
            SeqDecision.Reject
        }
    }

    fun markAccepted(seq: Int, encodedPdu: ByteArray, response: ByteArray?) {
        lastAccepted = seq
        lastAcceptedBytes = encodedPdu.copyOf()
        lastResponse = response?.copyOf()
        opened = true
    }

    fun cacheResponse(response: ByteArray) {
        lastResponse = response.copyOf()
    }

    fun reset() {
        lastAccepted = 255
        lastAcceptedBytes = null
        lastResponse = null
        opened = false
    }
}

class SeqCounter {
    private var next: Int = 0

    fun peek(): Int = next

    fun take(): Int {
        val value = next
        next = (next + 1) % ProtocolLimits.SEQ_MODULUS
        return value
    }

    fun reset() {
        next = 0
    }
}
