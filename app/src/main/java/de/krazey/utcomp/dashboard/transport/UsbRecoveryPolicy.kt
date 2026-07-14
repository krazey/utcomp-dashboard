package de.krazey.utcomp.dashboard.transport

import de.krazey.utcomp.dashboard.protocol.UsbPacket

internal object UsbRecoveryPolicy {
    const val FAST_RECONNECT_DELAY_MS = 150L
    const val NORMAL_RECONNECT_DELAY_MS = 2500L

    private const val REQUEST_WRITE_ATTEMPTS = 3
    private const val DEFAULT_WRITE_ATTEMPTS = 1
    private const val WRITE_RETRY_DELAY_MS = 20L

    // Only polling requests are idempotent. Retrying other commands could
    // duplicate configuration writes or actions after an ambiguous timeout.
    fun writeAttempts(command: Int): Int =
        if (command == UsbPacket.CMD_REQ_DATA) {
            REQUEST_WRITE_ATTEMPTS
        } else {
            DEFAULT_WRITE_ATTEMPTS
        }

    fun writeRetryDelayMs(failedAttempt: Int): Long =
        WRITE_RETRY_DELAY_MS * failedAttempt.coerceAtLeast(1)

    fun isCompleteWrite(result: Int, expectedBytes: Int): Boolean =
        result == expectedBytes
}
