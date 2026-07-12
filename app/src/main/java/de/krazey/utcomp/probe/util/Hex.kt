package de.krazey.utcomp.probe.util

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

fun Byte.u8(): Int = toInt() and 0xff

fun ByteArray.hex(limit: Int = size): String {
    val count = limit.coerceIn(0, size)
    if (count == 0) return ""

    return buildString(count * 3 - 1) {
        for (index in 0 until count) {
            if (index > 0) append(' ')
            val value = this@hex[index].u8()
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}
