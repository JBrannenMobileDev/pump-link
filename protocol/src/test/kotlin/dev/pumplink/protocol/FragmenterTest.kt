package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FragmenterTest {

    @Test
    fun `a message that fits is one fragment with FIRST and LAST`() {
        val message = ByteArray(10) { it.toByte() }
        val fragments = Fragmenter.fragment(message, ProtocolLimits.MIN_ATT_MTU)
        assertEquals(1, fragments.size)
        val header = FragmentHeader(fragments[0][0].toInt() and 0xFF)
        assertTrue(header.first && header.last && header.index == 0)
    }

    @Test
    fun `minimum MTU yields nineteen-octet payloads`() {
        assertEquals(19, Fragmenter.payloadMax(ProtocolLimits.MIN_ATT_MTU))
    }

    @Test
    fun `a 200-octet PDU splits at MTU 23 and reassembles`() {
        val message = ByteArray(200) { it.toByte() }
        val fragments = Fragmenter.fragment(message, ProtocolLimits.MIN_ATT_MTU)
        assertTrue(fragments.size > 1)
        val reassembler = Reassembler()
        var result: ReassemblyResult = ReassemblyResult.Incomplete
        for (fragment in fragments) {
            result = reassembler.accept(fragment)
        }
        val complete = assertIs<ReassemblyResult.Complete>(result)
        assertTrue(complete.message.contentEquals(message))
    }

    @Test
    fun `MTU 517 keeps a maximum message in one fragment`() {
        val message = ByteArray(ProtocolLimits.MAX_MESSAGE_SIZE) { 1 }
        val fragments = Fragmenter.fragment(message, ProtocolLimits.MAX_ATT_MTU)
        assertEquals(1, fragments.size)
    }

    @Test
    fun `an index gap discards the buffer`() {
        val fragments = Fragmenter.fragment(ByteArray(40) { 2 }, ProtocolLimits.MIN_ATT_MTU)
        val reassembler = Reassembler()
        reassembler.accept(fragments[0])
        val spliced = fragments[1].copyOf()
        spliced[0] = FragmentHeader.of(first = false, last = true, index = 3).byte.toByte()
        val result = reassembler.accept(spliced)
        assertEquals(ReassemblyResult.Discarded, result)
    }

    @Test
    fun `a fragment without FIRST when no buffer is open is discarded`() {
        val header = FragmentHeader.of(first = false, last = true, index = 1)
        val result = Reassembler().accept(byteArrayOf(header.byte.toByte(), 0x01))
        assertEquals(ReassemblyResult.Discarded, result)
    }

    @Test
    fun `FIRST discards a partial buffer`() {
        val first = Fragmenter.fragment(ByteArray(40) { 3 }, ProtocolLimits.MIN_ATT_MTU)
        val reassembler = Reassembler()
        reassembler.accept(first[0])
        val replacement = Fragmenter.fragment(byteArrayOf(9, 8, 7), ProtocolLimits.MIN_ATT_MTU)
        val result = reassembler.accept(replacement[0])
        val complete = assertIs<ReassemblyResult.Complete>(result)
        assertTrue(complete.message.contentEquals(byteArrayOf(9, 8, 7)))
    }
}
