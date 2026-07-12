package de.krazey.utcomp.dashboard.utcomp

import kotlin.math.pow
import kotlin.math.roundToInt

internal fun ByteArray.u8(offset: Int): Int =
    if (offset in indices) this[offset].toInt() and 0xff else 0

internal fun ByteArray.u16le(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8)

internal fun ByteArray.u32le(offset: Int): Long =
    u8(offset).toLong() or
        (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or
        (u8(offset + 3).toLong() shl 24)

internal fun ByteArray.f32le(offset: Int): Float {
    if (offset < 0 || offset + Float.SIZE_BYTES > size) return Float.NaN
    val bits = u8(offset) or
        (u8(offset + 1) shl 8) or
        (u8(offset + 2) shl 16) or
        (u8(offset + 3) shl 24)
    return Float.fromBits(bits)
}

internal fun Float.pretty(digits: Int = 2): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "+Inf" else "-Inf"
    val scale = 10.0.pow(digits.toDouble()).toFloat()
    return ((this * scale).roundToInt() / scale).toString()
}
