package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class SequenceWrapTest {

    @Test
    fun `every Seq is accepted or rejected correctly across the 8-bit wrap`() {
        for (last in 0..255) {
            val window = SequenceWindow()
            val accepted = byteArrayOf(last.toByte(), 1)
            window.markAccepted(last, accepted, response = null)
            for (seq in 0..255) {
                val incoming = byteArrayOf(seq.toByte(), 2)
                val decision = window.decide(seq, incoming)
                val delta = (seq - last + ProtocolLimits.SEQ_MODULUS) % ProtocolLimits.SEQ_MODULUS
                val expected = when {
                    seq == last -> SeqDecision.Reject
                    delta in 1..127 -> SeqDecision.Accept
                    else -> SeqDecision.Reject
                }
                assertEquals(expected, decision, "last=$last seq=$seq delta=$delta")
            }
        }
    }

    @Test
    fun `the first Seq of zero is inside the window before any mark`() {
        val window = SequenceWindow()
        assertEquals(SeqDecision.Accept, window.decide(0, byteArrayOf(0)))
        assertEquals(SeqDecision.Reject, window.decide(200, byteArrayOf(1)))
    }
}
