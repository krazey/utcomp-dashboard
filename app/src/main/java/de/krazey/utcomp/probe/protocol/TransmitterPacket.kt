package de.krazey.utcomp.probe.protocol

import de.krazey.utcomp.probe.util.hex

/** Protocol-level packet, independent of USB/Bluetooth framing. */
@Suppress("unused")
data class TransmitterPacket(
    val cmd: TransmitterConstants.Command,
    val pid: Int,
    val source: TransmitterConstants.Source,
    val ack: TransmitterConstants.Ack,
    val data: List<Byte> = emptyList(),
) {
    val dataLength: Int get() = data.size
    val pidMsb: Byte get() = ((pid ushr 8) and 0xff).toByte()
    val pidLsb: Byte get() = (pid and 0xff).toByte()

    override fun toString(): String =
        "TXP cmd=$cmd pid=0x%04X source=$source ack=$ack len=$dataLength data=${data.hex(32)}".format(pid)

    @Suppress("unused")
    companion object {
        fun request(pid: Int): TransmitterPacket = TransmitterPacket(
            cmd = TransmitterConstants.Command.REQ_DATA,
            pid = pid,
            source = TransmitterConstants.Source.HOST,
            ack = TransmitterConstants.Ack.ACK,
        )

        fun transfer(
            pid: Int,
            data: List<Byte>,
            source: TransmitterConstants.Source = TransmitterConstants.Source.HOST,
            ack: TransmitterConstants.Ack = TransmitterConstants.Ack.NACK,
        ): TransmitterPacket = TransmitterPacket(
            cmd = TransmitterConstants.Command.TRANSFER_DATA,
            pid = pid,
            source = source,
            ack = ack,
            data = data,
        )

        fun command(cmd: TransmitterConstants.Command, data: List<Byte> = emptyList()): TransmitterPacket =
            TransmitterPacket(cmd, 0, TransmitterConstants.Source.HOST, TransmitterConstants.Ack.NACK, data)
    }
}
