package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class Crc16Test {

    /**
     * The published check value for CRC-16/CCITT-FALSE. Without this vector the
     * implementation is only self-consistent, which is exactly how two ends of
     * a link end up agreeing on the wrong algorithm.
     */
    @Test
    fun `matches the published check value`() {
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `empty input yields the initial value`() {
        assertEquals(0xFFFF, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun `a single flipped bit changes the result`() {
        val clean = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val dirty = byteArrayOf(0x10, 0x21, 0x30, 0x40)
        assertNotEquals(Crc16.compute(clean), Crc16.compute(dirty))
    }

    /** Truncation has to be detectable; the PDU carries no length field. */
    @Test
    fun `truncation changes the result`() {
        val full = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertNotEquals(Crc16.compute(full), Crc16.compute(full, toIndex = 4))
    }

    @Test
    fun `respects an explicit range`() {
        val padded = byteArrayOf(0x7F, 0x31, 0x32, 0x33, 0x7F)
        assertEquals(
            Crc16.compute("123".toByteArray(Charsets.US_ASCII)),
            Crc16.compute(padded, fromIndex = 1, toIndex = 4),
        )
    }

    @Test
    fun `rejects a range outside the array`() {
        assertFailsWith<IllegalArgumentException> {
            Crc16.compute(ByteArray(4), fromIndex = 0, toIndex = 5)
        }
    }

    @Test
    fun `result always fits in sixteen bits`() {
        val bytes = ByteArray(256) { it.toByte() }
        for (length in 0..bytes.size) {
            val crc = Crc16.compute(bytes, toIndex = length)
            assertEquals(crc, crc and 0xFFFF, "CRC escaped 16 bits at length $length")
        }
    }
}
