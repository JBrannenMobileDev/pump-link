package dev.pumplink.protocol

/**
 * CRC-16/CCITT-FALSE over a PDU, as specified in docs/02-protocol.md.
 *
 * Polynomial 0x1021, initial value 0xFFFF, no input or output reflection, no
 * final XOR. The check value for the ASCII string `123456789` is 0x29B1.
 *
 * This detects corruption, not tampering. Tamper resistance is the session MAC;
 * a CRC is trivially recomputed by anyone who alters the payload.
 */
object Crc16 {

    private const val POLYNOMIAL = 0x1021
    private const val INITIAL = 0xFFFF
    private const val MASK = 0xFFFF
    private const val HIGH_BIT = 0x8000
    private const val BITS_PER_BYTE = 8

    /** Computes the CRC over `bytes` in `[fromIndex, toIndex)`. */
    fun compute(bytes: ByteArray, fromIndex: Int = 0, toIndex: Int = bytes.size): Int {
        require(fromIndex in 0..toIndex && toIndex <= bytes.size) {
            "range $fromIndex..<$toIndex is not within 0..<${bytes.size}"
        }

        var crc = INITIAL
        for (index in fromIndex until toIndex) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl BITS_PER_BYTE)
            repeat(BITS_PER_BYTE) {
                crc = if (crc and HIGH_BIT != 0) {
                    (crc shl 1) xor POLYNOMIAL
                } else {
                    crc shl 1
                }
                crc = crc and MASK
            }
        }
        return crc
    }
}
