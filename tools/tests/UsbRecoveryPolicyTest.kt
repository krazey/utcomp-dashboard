package de.krazey.utcomp.dashboard.transport

import de.krazey.utcomp.dashboard.protocol.UsbPacket

fun main() {
    check(UsbRecoveryPolicy.FAST_RECONNECT_DELAY_MS == 150L)
    check(UsbRecoveryPolicy.NORMAL_RECONNECT_DELAY_MS == 2500L)

    check(
        UsbRecoveryPolicy.writeAttempts(UsbPacket.CMD_REQ_DATA) == 3,
    )
    check(
        UsbRecoveryPolicy.writeAttempts(UsbPacket.CMD_TRANSFER_DATA) == 1,
    )
    check(
        UsbRecoveryPolicy.writeAttempts(UsbPacket.CMD_TRANSFER_STATUS) == 1,
    )

    check(UsbRecoveryPolicy.writeRetryDelayMs(1) == 20L)
    check(UsbRecoveryPolicy.writeRetryDelayMs(2) == 40L)
    check(UsbRecoveryPolicy.writeRetryDelayMs(0) == 20L)

    println("USB recovery policy tests passed")
}
