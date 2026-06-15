package de.krazey.utcomp.probe.protocol

import de.krazey.utcomp.probe.util.hex
import de.krazey.utcomp.probe.util.u8

/**
 * Native Kotlin port of the decompiled RCOMP.Communication.UsbPacket.
 * UTCOMP USB reports are 64 bytes; protocol payload begins at byte 16.
 */
data class UsbPacket(
    val cmd: Int,
    val pid: Int = 0,
    val direction: Int = 0,
    val ack: Int = 0,
    val length: Int = 0,
    val split: Int = 0,
    val data: List<Byte> = emptyList(),
    private val rawReport: ByteArray = ByteArray(REPORT_SIZE),
) {
    enum class DataSplit(val id: Int) {
        NONE(0), BUFFER_START(1), BUFFER_IN_PROGRESS(2), STOP(3);
        companion object {
            fun fromId(id: Int): DataSplit = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    fun toReport(): ByteArray = rawReport.copyOf()

    override fun toString(): String = when (cmd) {
        CMD_TRANSFER_DATA -> {
            val dir = if (direction == 1) "TX" else "RX"
            "USB $dir DATA pid=0x%04X len=$length split=${DataSplit.fromId(split)} ack=$ack data=${data.hex(32)}".format(pid)
        }
        CMD_TRANSFER_STATUS -> "USB STATUS dir=${rawReport.getOrNull(1)?.u8()} status=0x%02X".format(rawReport.getOrNull(2)?.u8() ?: 0)
        CMD_REQ_DATA -> "USB REQ DATA pid=0x%04X".format(pid)
        else -> "USB REPORT cmd=0x%02X raw=${rawReport.hex(64)}".format(cmd)
    }

    companion object {
        const val REPORT_SIZE = 64
        const val DATA_LENGTH = 48

        const val CMD_UNKNOWN = 0
        const val CMD_TRANSFER_DATA = 1
        const val CMD_TRANSFER_STATUS = 2
        const val CMD_REQ_DATA = 3

        fun parse(reportIn: ByteArray): UsbPacket {
            val report = ByteArray(REPORT_SIZE)
            System.arraycopy(reportIn, 0, report, 0, reportIn.size.coerceAtMost(REPORT_SIZE))
            val cmd = report[0].u8()
            return when (cmd) {
                CMD_TRANSFER_DATA -> {
                    val pid = (report[1].u8() shl 8) or report[2].u8()
                    val length = (report[5].u8() shl 24) or
                        (report[6].u8() shl 16) or
                        (report[7].u8() shl 8) or
                        report[8].u8()
                    UsbPacket(
                        cmd = cmd,
                        pid = pid,
                        direction = report[3].u8(),
                        ack = report[4].u8(),
                        length = length,
                        split = report[15].u8(),
                        data = report.copyOfRange(16, 64).toList(),
                        rawReport = report,
                    )
                }
                CMD_REQ_DATA -> UsbPacket(
                    cmd = cmd,
                    pid = (report[1].u8() shl 8) or report[2].u8(),
                    rawReport = report,
                )
                else -> UsbPacket(
                    cmd = cmd,
                    data = report.copyOfRange(1, REPORT_SIZE).toList(),
                    rawReport = report,
                )
            }
        }

        fun transferData(pid: Int, ack: Int, length: Int, split: Int, txData: List<Byte>): UsbPacket {
            require(txData.size <= DATA_LENGTH) { "USB packet payload may not exceed $DATA_LENGTH bytes" }
            val report = ByteArray(REPORT_SIZE)
            report[0] = CMD_TRANSFER_DATA.toByte()
            report[1] = ((pid ushr 8) and 0xff).toByte()
            report[2] = (pid and 0xff).toByte()
            report[3] = 1 // HOST_TO_DEVICE
            report[4] = (ack and 0xff).toByte()
            report[5] = ((length ushr 24) and 0xff).toByte()
            report[6] = ((length ushr 16) and 0xff).toByte()
            report[7] = ((length ushr 8) and 0xff).toByte()
            report[8] = (length and 0xff).toByte()
            report[15] = (split and 0xff).toByte()
            txData.forEachIndexed { index, b -> report[16 + index] = b }
            return parse(report)
        }

        fun requestData(pid: Int, source: Int = 1): UsbPacket {
            val report = ByteArray(REPORT_SIZE)
            report[0] = CMD_REQ_DATA.toByte()
            report[1] = ((pid ushr 8) and 0xff).toByte()
            report[2] = (pid and 0xff).toByte()
            report[3] = (source and 0xff).toByte()
            return parse(report)
        }

        fun command(cmd: Int, values: List<Byte> = emptyList()): UsbPacket {
            val report = ByteArray(REPORT_SIZE)
            report[0] = (cmd and 0xff).toByte()
            values.take(REPORT_SIZE - 1).forEachIndexed { index, b -> report[index + 1] = b }
            return parse(report)
        }
    }
}
