package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SequenceWindowTest {

    @Test
    fun `the first Seq of zero is accepted`() {
        val window = SequenceWindow()
        assertEquals(SeqDecision.Accept, window.decide(0, byteArrayOf(1)))
    }

    @Test
    fun `a byte-identical repeat is a retransmission`() {
        val window = SequenceWindow()
        val bytes = byteArrayOf(1, 2, 3)
        window.markAccepted(0, bytes, response = byteArrayOf(9))
        val decision = window.decide(0, bytes)
        val retransmit = assertIs<SeqDecision.Retransmit>(decision)
        assertTrue(retransmit.cachedResponse.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `a gap larger than 127 is rejected`() {
        val window = SequenceWindow()
        window.markAccepted(0, byteArrayOf(1), null)
        assertEquals(SeqDecision.Reject, window.decide(200, byteArrayOf(2)))
    }

    @Test
    fun `seq 1 after 0 is accepted`() {
        val window = SequenceWindow()
        window.markAccepted(0, byteArrayOf(1), null)
        assertEquals(SeqDecision.Accept, window.decide(1, byteArrayOf(2)))
    }
}
