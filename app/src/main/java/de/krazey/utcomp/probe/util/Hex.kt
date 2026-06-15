package de.krazey.utcomp.probe.util

fun Byte.u8(): Int = toInt() and 0xff

fun ByteArray.hex(limit: Int = size): String =
    take(limit.coerceAtMost(size)).joinToString(" ") { "%02X".format(it.u8()) }

fun List<Byte>.hex(limit: Int = size): String =
    take(limit.coerceAtMost(size)).joinToString(" ") { "%02X".format(it.u8()) }
