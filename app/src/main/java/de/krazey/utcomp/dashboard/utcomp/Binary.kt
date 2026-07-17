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

internal fun ByteArray.putU16le(offset: Int, value: Int) {
    require(offset >= 0 && offset + 1 < size) { "u16 write exceeds payload" }
    require(value in 0..0xffff) { "u16 value out of range: $value" }
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

internal fun ByteArray.putF32le(offset: Int, value: Float) {
    require(offset >= 0 && offset + Float.SIZE_BYTES <= size) {
        "f32 write exceeds payload"
    }
    val bits = value.toBits()
    this[offset] = (bits and 0xff).toByte()
    this[offset + 1] = ((bits ushr 8) and 0xff).toByte()
    this[offset + 2] = ((bits ushr 16) and 0xff).toByte()
    this[offset + 3] = ((bits ushr 24) and 0xff).toByte()
}

internal fun Float.pretty(digits: Int = 2): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "+Inf" else "-Inf"
    val scale = 10.0.pow(digits.toDouble()).toFloat()
    return ((this * scale).roundToInt() / scale).toString()
}
