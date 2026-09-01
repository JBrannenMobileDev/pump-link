package dev.pumplink.protocol

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int =
    (u8(index) shl 8) or u8(index + 1)

internal fun ByteArray.u32(index: Int): UInt =
    ((u8(index).toLong() shl 24) or
        (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or
        u8(index + 3).toLong()).toUInt()

internal fun ByteArray.u64(index: Int): ULong {
    var value = 0uL
    for (offset in 0 until 8) {
        value = (value shl 8) or u8(index + offset).toULong()
    }
    return value
}

internal fun MutableList<Byte>.putU8(value: Int) {
    add((value and 0xFF).toByte())
}

internal fun MutableList<Byte>.putU16(value: Int) {
    putU8(value ushr 8)
    putU8(value)
}

internal fun MutableList<Byte>.putU32(value: UInt) {
    putU8((value shr 24).toInt())
    putU8((value shr 16).toInt())
    putU8((value shr 8).toInt())
    putU8(value.toInt())
}

internal fun MutableList<Byte>.putU64(value: ULong) {
    for (shift in 56 downTo 0 step 8) {
        putU8((value shr shift).toInt())
    }
}

internal fun ByteArray.copyOfRangeOrEmpty(from: Int, to: Int): ByteArray =
    if (from >= to) ByteArray(0) else copyOfRange(from, to)
