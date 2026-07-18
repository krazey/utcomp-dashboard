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

    check(!UsbRecoveryPolicy.isCompleteWrite(-1, 64))
    check(!UsbRecoveryPolicy.isCompleteWrite(0, 64))
    check(!UsbRecoveryPolicy.isCompleteWrite(63, 64))
    check(UsbRecoveryPolicy.isCompleteWrite(64, 64))

    check(
        UsbRecoveryPolicy.shouldKeepSessionAfterWriteFailure(
            UsbPacket.CMD_REQ_DATA,
            connectedForMs = 1499L,
            lastRxAgoMs = null,
        ),
    )
    check(
        !UsbRecoveryPolicy.shouldKeepSessionAfterWriteFailure(
            UsbPacket.CMD_REQ_DATA,
            connectedForMs = 1500L,
            lastRxAgoMs = null,
        ),
    )
    check(
        UsbRecoveryPolicy.shouldKeepSessionAfterWriteFailure(
            UsbPacket.CMD_REQ_DATA,
            connectedForMs = 5000L,
            lastRxAgoMs = 1000L,
        ),
    )
    check(
        !UsbRecoveryPolicy.shouldKeepSessionAfterWriteFailure(
            UsbPacket.CMD_REQ_DATA,
            connectedForMs = 5000L,
            lastRxAgoMs = 1001L,
        ),
    )
    check(
        !UsbRecoveryPolicy.shouldKeepSessionAfterWriteFailure(
            UsbPacket.CMD_TRANSFER_DATA,
            connectedForMs = 1L,
            lastRxAgoMs = 1L,
        ),
    )

    println("USB recovery policy tests passed")
}
