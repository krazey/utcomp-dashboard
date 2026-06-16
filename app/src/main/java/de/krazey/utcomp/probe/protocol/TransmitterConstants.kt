package de.krazey.utcomp.probe.protocol

object TransmitterConstants {
    @Suppress("unused")
enum class Command(val id: Int) {
        UNKNOWN(0),
        TRANSFER_DATA(1),
        TRANSFER_STATUS(2),
        REQ_DATA(3),
        JUMP_TO_BOOTLOADER(4),
        ERASE_EEPROM(5),
        RESET_UTCOMP(6),
        RESET_STATS(7),
        JMP_TO_BOOTLOADER(8);

        companion object {
            fun fromId(id: Int): Command = entries.firstOrNull { it.id == id } ?: UNKNOWN
        }
    }

    enum class Source(val id: Int) {
        HOST(1), DEVICE(2);
        companion object {
            fun fromId(id: Int): Source = entries.firstOrNull { it.id == id } ?: HOST
        }
    }

    enum class Ack(val id: Int) {
        NACK(0), ACK(1);
        companion object {
            fun fromId(id: Int): Ack = entries.firstOrNull { it.id == id } ?: NACK
        }
    }

    @Suppress("unused")
object UtcompPid {
        const val TEMPERATURES_SETTINGS = 0x1004
        const val GPIO_SETTINGS = 0x1009
        const val ANALOG_SETTINGS1 = 0x100A
        const val ANALOG_SETTINGS2 = 0x100B
        const val GENERAL_SETTINGS1 = 0x100C
        const val ANALOG_OSC_SETTINGS1 = 0x1017
        const val ANALOG_OSC_SETTINGS2 = 0x1018
        const val USERSCREEN_SETTINGS = 0x1019

        const val FIRMWARE = 0x0001
        const val TEMPERATURES_DATA = 0x1001
        const val VSS_DATA = 0x1003
        const val VSS_SETTINGS = 0x1004
        const val CONSUMPTION_DATA = 0x1005
        const val CONSUMPTION_SETTINGS = 0x1006
        const val GENERAL_DATA1 = 0x1016
        const val GENERAL_DATA2 = 0x101E
        const val TRIP_DATA = 0x101F
        const val DATA_STOP = 0x1031
        const val FLASH_IMAGE = 0x2002
        const val FLASH_STOP = 0x2003
    }
}
