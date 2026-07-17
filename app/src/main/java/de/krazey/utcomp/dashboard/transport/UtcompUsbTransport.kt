package de.krazey.utcomp.dashboard.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import de.krazey.utcomp.dashboard.protocol.TransmitterPacket
import de.krazey.utcomp.dashboard.protocol.TransmitterPacketParser
import de.krazey.utcomp.dashboard.protocol.UsbPacket
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.dashboard.utcomp.UtcompDecoder
import de.krazey.utcomp.dashboard.util.hex
import java.io.Closeable
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class UtcompUsbTransport(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val onPacketReceived: (TransmitterPacket) -> Unit = {},
    private val onDecodedSnapshot: (UtcompDataSnapshot, Int) -> Unit = { _, _ -> },
) : Closeable {
    companion object {
        const val TAG = "UTCOMPDashboard"
        const val VID = 1003
        const val PID = 52131

        private const val TX_QUEUE_CAPACITY = 256
        private const val USB_TRANSFER_TIMEOUT_MS = 100
    }

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val txQueue = LinkedBlockingQueue<UsbPacket>(TX_QUEUE_CAPACITY)

    @Volatile var verboseLogging = false

    @Volatile private var running = false
    @Volatile private var sessionId = 0L
    private var registered = false
    private var device: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    @Volatile
    private var connectedAtMs = 0L

    @Volatile
    private var lastRxAtMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java,
                    )
                    logLine("USB permission result: granted=$granted device=${dev?.deviceName}")
                    if (granted && dev != null) connect(dev)
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    logLine("USB attached")
                    requestPermissionAndConnect()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    logLine("USB detached")
                    closeWithReason("device detached")
                }
            }
        }
    }

    @Synchronized
    fun register() {
        if (registered) return

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        registered = true
        logLine("USB receiver registered")
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        logLine("USB receiver unregistered")
    }

    fun findDevice(logDiscovery: Boolean = true): UsbDevice? {
        val devices = manager.deviceList.values
        if (logDiscovery) {
            if (devices.isEmpty()) {
                logLine("No USB host devices visible to Android. Check OTG/host mode and cable.")
            } else {
                logLine("Visible USB devices:")
                devices.forEach { logLine("  ${describeDevice(it)}") }
            }
        }

        val dev = devices.firstOrNull { it.vendorId == VID && it.productId == PID }
        if (logDiscovery) {
            if (dev == null) {
                logLine("UTCOMP USB device not found. Looking for VID=$VID PID=$PID.")
            } else {
                logLine("UTCOMP USB device found: ${describeDevice(dev)}")
                logInterfaces(dev)
            }
        }
        return dev
    }

    fun requestPermissionAndConnect(logDiscovery: Boolean = true) {
        val dev = findDevice(logDiscovery) ?: return
        device = dev
        if (manager.hasPermission(dev)) {
            if (logDiscovery) logLine("USB permission already granted")
            connect(dev)
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction), flags)
        logLine("Requesting USB permission")
        manager.requestPermission(dev, intent)
    }

    @Synchronized
    fun connect(dev: UsbDevice? = device ?: findDevice()) {
        if (dev == null) {
            logLine("USB connect requested but no matching device is available")
            return
        }

        if (running && connection != null && device?.deviceName == dev.deviceName) {
            verboseLog { "USB already connected to ${dev.deviceName}, ignoring duplicate connect" }
            return
        }

        closeLocked(notify = true, reason = "replaced by new connection")

        try {
            logLine("Opening USB device: ${describeDevice(dev)}")
            logInterfaces(dev)

            val intf = dev.getInterface(0)
            val readEp = findEndpoint(intf, android.hardware.usb.UsbConstants.USB_DIR_IN)
                ?: intf.getEndpoint(0)
            val writeEp = findEndpoint(intf, android.hardware.usb.UsbConstants.USB_DIR_OUT)
                ?: intf.getEndpoint(1)

            logLine(
                "Using interface 0, read=${describeEndpoint(readEp)}, " +
                    "write=${describeEndpoint(writeEp)}",
            )

            val conn = openAndClaim(dev, intf)
            if (conn == null) {
                logLine("USB claim failed. If kernel log shows CLAIMINTERFACE ret=-16, the interface is busy.")
                logLine("Try: force-stop/uninstall the original UTCOMP app, reconnect USB, then connect again.")
                return
            }

            device = dev
            usbInterface = intf
            readEndpoint = readEp
            writeEndpoint = writeEp
            connection = conn
            txQueue.clear()
            running = true
            connectedAtMs = SystemClock.elapsedRealtime()
            lastRxAtMs = 0L
            val activeSession = ++sessionId
            logLine("USB connected")
            onConnectionChanged(true)
            startReadLoop(conn, readEp, activeSession)
            startWriteLoop(conn, writeEp, activeSession)
        } catch (t: Throwable) {
            logLine("USB connect failed: ${t.stackTraceToString()}")
            closeLocked(notify = true, reason = "connect failure")
        }
    }

    private fun openAndClaim(dev: UsbDevice, intf: UsbInterface): UsbDeviceConnection? {
        var conn = manager.openDevice(dev)
        if (conn == null) {
            logLine("USB openDevice() returned null")
            return null
        }

        if (conn.claimInterface(intf, true)) {
            logLine("USB claimInterface(force=true) OK")
            return conn
        }

        logLine("USB claimInterface(force=true) failed, retrying once after close/reopen")
        runCatching { conn.close() }
        Thread.sleep(250)

        conn = manager.openDevice(dev)
        if (conn == null) {
            logLine("USB openDevice() returned null on retry")
            return null
        }

        if (conn.claimInterface(intf, true)) {
            logLine("USB claimInterface(force=true) OK on retry")
            return conn
        }

        runCatching { conn.close() }
        return null
    }

    fun write(packet: UsbPacket) {
        if (!running) {
            verboseLog { "Ignoring USB write while disconnected: $packet" }
            return
        }

        if (!txQueue.offer(packet)) {
            logLine("USB write queue full; dropping pid=0x%04X".format(packet.pid))
            return
        }
        verboseLog { "queued $packet" }
    }

    fun write(packet: TransmitterPacket) {
        TransmitterPacketParser.toUsbPackets(packet).forEach(::write)
    }

    private fun startReadLoop(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        activeSession: Long,
    ) {
        readThread = thread(name = "utcomp-usb-read", isDaemon = true) {
            val buffer = ByteArray(UsbPacket.REPORT_SIZE)
            val transferLength = ep.maxPacketSize.coerceIn(1, UsbPacket.REPORT_SIZE)
            var stopReason = "read loop stopped unexpectedly"

            while (isSessionActive(activeSession)) {
                try {
                    val read = conn.bulkTransfer(
                        ep,
                        buffer,
                        transferLength,
                        USB_TRANSFER_TIMEOUT_MS,
                    )
                    if (read <= 0) continue

                    lastRxAtMs = SystemClock.elapsedRealtime()
                    if (read < buffer.size) {
                        Arrays.fill(buffer, read, buffer.size, 0.toByte())
                    }
                    verboseLog { "USB raw[$read]: ${buffer.hex(read)}" }

                    val usb = UsbPacket.parse(buffer)
                    verboseLog { usb.toString() }
                    val txp = TransmitterPacketParser.fromUsb(usb) ?: continue
                    verboseLog { txp.toString() }

                    runCatching { onPacketReceived(txp) }
                        .onFailure { error ->
                            logLine(
                                "Incoming packet callback failed for pid=0x%04X: %s".format(
                                    txp.pid,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }

                    val debugLog = if (verboseLogging) {
                        { line: String -> logLine("DECODE $line") }
                    } else {
                        null
                    }
                    if (UtcompDecoder.apply(txp, debugLog)) {
                        onDecodedSnapshot(UtcompDecoder.snapshot, txp.pid)
                    }
                } catch (t: Throwable) {
                    if (isSessionActive(activeSession)) {
                        stopReason = "read exception ${t.javaClass.simpleName}"
                        logLine("USB read failed: ${t.stackTraceToString()}")
                    }
                    break
                }
            }
            if (isSessionActive(activeSession)) {
                closeSession(activeSession, stopReason)
            }
            verboseLog { "USB read loop stopped" }
        }
    }

    private fun startWriteLoop(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        activeSession: Long,
    ) {
        writeThread = thread(name = "utcomp-usb-write", isDaemon = true) {
            while (isSessionActive(activeSession)) {
                try {
                    val packet = txQueue.take()
                    if (!isSessionActive(activeSession)) break

                    val failureReason = writePacketWithRetry(
                        conn = conn,
                        ep = ep,
                        packet = packet,
                        activeSession = activeSession,
                    )
                    if (failureReason != null) {
                        if (isSessionActive(activeSession)) {
                            closeSession(activeSession, failureReason)
                        }
                        break
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            verboseLog { "USB write loop stopped" }
        }
    }

    @Throws(InterruptedException::class)
    private fun writePacketWithRetry(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        packet: UsbPacket,
        activeSession: Long,
    ): String? {
        val bytes = packet.toReport()
        val attempts = UsbRecoveryPolicy.writeAttempts(packet.cmd)
        var lastResult: Int? = null
        var lastError: Throwable? = null

        for (attempt in 1..attempts) {
            if (!isSessionActive(activeSession)) return "session stopped"

            try {
                val written = conn.bulkTransfer(
                    ep,
                    bytes,
                    bytes.size,
                    USB_TRANSFER_TIMEOUT_MS,
                )
                verboseLog { "USB write[$written]: ${bytes.hex(64)}" }
                if (UsbRecoveryPolicy.isCompleteWrite(written, bytes.size)) {
                    if (attempt > 1) {
                        logLine(
                            "USB write recovered after ${attempt - 1} retry(s): " +
                                writeContext(packet),
                        )
                    }
                    return null
                }
                lastResult = written
                lastError = null
            } catch (e: Exception) {
                lastResult = null
                lastError = e
            }

            if (attempt < attempts) {
                logLine(
                    "USB write retry $attempt/${attempts - 1}: " +
                        failureContext(packet, lastResult, lastError),
                )
                Thread.sleep(UsbRecoveryPolicy.writeRetryDelayMs(attempt))
            }
        }

        return "write retries exhausted: ${failureContext(packet, lastResult, lastError)}"
    }

    private fun failureContext(
        packet: UsbPacket,
        result: Int?,
        error: Throwable?,
    ): String {
        val outcome = if (error != null) {
            "error=${error.javaClass.simpleName}:${error.message ?: "no message"}"
        } else {
            "result=$result"
        }
        return "$outcome ${writeContext(packet)}"
    }

    private fun writeContext(packet: UsbPacket): String {
        val nowMs = SystemClock.elapsedRealtime()
        val connectedForMs = (nowMs - connectedAtMs).coerceAtLeast(0L)
        val lastRxAgo = if (lastRxAtMs > 0L) {
            "${(nowMs - lastRxAtMs).coerceAtLeast(0L)}ms"
        } else {
            "none"
        }
        return String.format(
            Locale.US,
            "cmd=0x%02X pid=0x%04X connectedFor=%dms lastRxAgo=%s queue=%d",
            packet.cmd,
            packet.pid,
            connectedForMs,
            lastRxAgo,
            txQueue.size,
        )
    }

    private fun isSessionActive(activeSession: Long): Boolean =
        running && sessionId == activeSession

    @Synchronized
    private fun closeSession(activeSession: Long, reason: String) {
        if (sessionId != activeSession) return
        closeLocked(notify = true, reason = reason)
    }

    @Synchronized
    override fun close() {
        closeLocked(notify = true, reason = "closed by app")
    }

    @Synchronized
    private fun closeWithReason(reason: String) {
        closeLocked(notify = true, reason = reason)
    }

    private fun closeLocked(notify: Boolean, reason: String) {
        val hadConnection = connection != null || running
        running = false
        sessionId++
        txQueue.clear()

        val currentThread = Thread.currentThread()
        if (writeThread !== currentThread) writeThread?.interrupt()
        writeThread = null
        readThread = null

        runCatching { connection?.releaseInterface(usbInterface) }
        runCatching { connection?.close() }
        connection = null
        usbInterface = null
        readEndpoint = null
        writeEndpoint = null

        if (hadConnection) {
            val nowMs = SystemClock.elapsedRealtime()
            val connectedForMs = (nowMs - connectedAtMs).coerceAtLeast(0L)
            val lastRxAgo = if (lastRxAtMs > 0L) {
                "${(nowMs - lastRxAtMs).coerceAtLeast(0L)}ms"
            } else {
                "none"
            }
            logLine(
                "USB closed: reason=$reason connectedFor=${connectedForMs}ms " +
                    "lastRxAgo=$lastRxAgo",
            )
            connectedAtMs = 0L
            lastRxAtMs = 0L
            if (notify) onConnectionChanged(false)
        }
    }

    private fun findEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? {
        for (index in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(index)
            if (endpoint.direction == direction) return endpoint
        }
        return null
    }

    private fun logInterfaces(dev: UsbDevice) {
        for (index in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(index)
            logLine(
                "  interface[$index]: class=${intf.interfaceClass} " +
                    "subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} " +
                    "endpoints=${intf.endpointCount}",
            )
            for (endpointIndex in 0 until intf.endpointCount) {
                logLine("    endpoint[$endpointIndex]: ${describeEndpoint(intf.getEndpoint(endpointIndex))}")
            }
        }
    }

    private fun describeDevice(dev: UsbDevice): String =
        "name=${dev.deviceName} vid=${dev.vendorId} pid=${dev.productId} " +
            "product=${dev.productName} manufacturer=${dev.manufacturerName} " +
            "interfaces=${dev.interfaceCount}"

    private fun describeEndpoint(ep: UsbEndpoint): String =
        "addr=${ep.address} dir=${directionName(ep.direction)} type=${ep.type} max=${ep.maxPacketSize}"

    private fun directionName(direction: Int): String =
        when (direction) {
            android.hardware.usb.UsbConstants.USB_DIR_IN -> "IN"
            android.hardware.usb.UsbConstants.USB_DIR_OUT -> "OUT"
            else -> direction.toString()
        }

    private inline fun verboseLog(message: () -> String) {
        if (verboseLogging) logLine(message())
    }

    private fun logLine(line: String) {
        log(line)
    }
}
