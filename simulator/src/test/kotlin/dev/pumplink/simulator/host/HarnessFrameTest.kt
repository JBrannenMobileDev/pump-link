package dev.pumplink.simulator.host

import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessFrameTest {

    @Test
    fun `round-trips a notify frame`() {
        val frame = HarnessFrame(HarnessFrame.NOTIFY, byteArrayOf(1, 2, 3))
        val encoded = frame.encode()
        assertEquals(2 + 1 + 3, encoded.size)
        assertEquals(0, encoded[0].toInt())
        assertEquals(4, encoded[1].toInt())
        assertEquals(HarnessFrame.NOTIFY, encoded[2].toInt() and 0xFF)
    }
}
