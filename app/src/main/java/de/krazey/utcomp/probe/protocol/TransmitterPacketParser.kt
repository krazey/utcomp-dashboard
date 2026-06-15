package de.krazey.utcomp.probe.protocol

/** Native Kotlin port of the USB path from RCOMP.Communication.TransmitterPacketParser. */
object TransmitterPacketParser {
    private val splitData = mutableListOf<Byte>()

    var splitProgress: Int = -1
        private set

    fun fromUsb(usb: UsbPacket): TransmitterPacket? {
        val ack = TransmitterConstants.Ack.fromId(usb.ack)
        val source = TransmitterConstants.Source.fromId(usb.direction)

        if (usb.split == UsbPacket.DataSplit.NONE.id) {
            splitData.clear()
            splitProgress = -1
            return TransmitterPacket.transfer(usb.pid, usb.data, source, ack)
        }

        return when (UsbPacket.DataSplit.fromId(usb.split)) {
            UsbPacket.DataSplit.BUFFER_START -> {
                splitData.clear()
                splitData += usb.data
                splitProgress = 0
                null
            }

            UsbPacket.DataSplit.BUFFER_IN_PROGRESS -> {
                splitData += usb.data
                splitProgress = if (usb.length > 0) {
                    (splitData.size * 100 / usb.length).coerceIn(0, 99)
                } else {
                    0
                }
                null
            }

            UsbPacket.DataSplit.STOP -> {
                splitData += usb.data
                splitProgress = 100
                if (usb.length > 0 && splitData.size >= usb.length) {
                    TransmitterPacket.transfer(usb.pid, splitData.take(usb.length), source, ack)
                } else {
                    TransmitterPacket.transfer(usb.pid, splitData.toList(), source, ack)
                }
            }

            UsbPacket.DataSplit.NONE -> null
        }
    }

    fun toUsbPackets(packet: TransmitterPacket): List<UsbPacket> {
        return when (packet.cmd) {
            TransmitterConstants.Command.TRANSFER_DATA -> {
                if (packet.dataLength <= UsbPacket.DATA_LENGTH) {
                    listOf(
                        UsbPacket.transferData(
                            pid = packet.pid,
                            ack = packet.ack.id,
                            length = packet.dataLength,
                            split = UsbPacket.DataSplit.NONE.id,
                            txData = packet.data,
                        ),
                    )
                } else {
                    splitToUsbPackets(packet)
                }
            }

            TransmitterConstants.Command.REQ_DATA -> {
                listOf(UsbPacket.requestData(packet.pid, packet.source.id))
            }

            else -> {
                listOf(UsbPacket.command(packet.cmd.id, packet.data))
            }
        }
    }

    private fun splitToUsbPackets(packet: TransmitterPacket): List<UsbPacket> {
        val out = mutableListOf<UsbPacket>()
        var remaining = packet.dataLength
        var offset = 0

        while (remaining > 0) {
            val take = remaining.coerceAtMost(UsbPacket.DATA_LENGTH)
            val split = when {
                offset == 0 -> UsbPacket.DataSplit.BUFFER_START.id
                remaining <= UsbPacket.DATA_LENGTH -> UsbPacket.DataSplit.STOP.id
                else -> UsbPacket.DataSplit.BUFFER_IN_PROGRESS.id
            }

            out += UsbPacket.transferData(
                pid = packet.pid,
                ack = packet.ack.id,
                length = packet.dataLength,
                split = split,
                txData = packet.data.subList(offset, offset + take),
            )

            offset += take
            remaining -= take
        }

        return out
    }
}
