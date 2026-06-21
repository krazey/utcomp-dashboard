package de.krazey.utcomp.probe.transport

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
import android.os.Build
import de.krazey.utcomp.probe.protocol.TransmitterPacket
import de.krazey.utcomp.probe.protocol.TransmitterPacketParser
import de.krazey.utcomp.probe.protocol.UsbPacket
import de.krazey.utcomp.probe.utcomp.UtcompDecoder
import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.probe.util.hex
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class UtcompUsbTransport(
    private val context: Context,
    private val uiLog: (String) -> Unit,
    private val onDecodedSnapshot: (UtcompDataSnapshot) -> Unit = {},
) : Closeable {
    companion object {
        const val TAG = "UTCOMPProbe"
        const val VID = 1003
        const val PID = 52131
    }

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val txQueue = LinkedBlockingQueue<UsbPacket>()

    @Volatile private var running = false
    private var device: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    logLine("USB permission result: granted=$granted device=${dev?.deviceName}")
                    if (granted && dev != null) connect(dev)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    logLine("USB attached")
                    requestPermissionAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    logLine("USB detached")
                    close()
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        logLine("USB receiver registered")
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        logLine("USB receiver unregistered")
    }

    fun findDevice(): UsbDevice? {
        val devices = manager.deviceList.values.toList()
        if (devices.isEmpty()) {
            logLine("No USB host devices visible to Android. Check OTG/host mode and cable.")
        } else {
            logLine("Visible USB devices:")
            devices.forEach { logLine("  ${describeDevice(it)}") }
        }

        val dev = devices.firstOrNull { it.vendorId == VID && it.productId == PID }
        if (dev == null) {
            logLine("UTCOMP USB device not found. Looking for VID=$VID PID=$PID.")
        } else {
            logLine("UTCOMP USB device found: ${describeDevice(dev)}")
            logInterfaces(dev)
        }
        return dev
    }

    fun requestPermissionAndConnect() {
        val dev = findDevice() ?: return
        device = dev
        if (manager.hasPermission(dev)) {
            logLine("USB permission already granted")
            connect(dev)
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction), flags)
        logLine("Requesting USB permission")
        manager.requestPermission(dev, intent)
    }

    fun connect(dev: UsbDevice? = device ?: findDevice()) {
        if (dev == null) {
            logLine("USB connect requested but no matching device is available")
            return
        }

        if (connection != null && device?.deviceName == dev.deviceName) {
            logLine("USB already connected to ${dev.deviceName}, ignoring duplicate connect")
            return
        }

        close()

        try {
            logLine("Opening USB device: ${describeDevice(dev)}")
            logInterfaces(dev)

            val intf = dev.getInterface(0)
            val readEp = findEndpoint(intf, android.hardware.usb.UsbConstants.USB_DIR_IN) ?: intf.getEndpoint(0)
            val writeEp = findEndpoint(intf, android.hardware.usb.UsbConstants.USB_DIR_OUT) ?: intf.getEndpoint(1)

            logLine("Using interface 0, read=${describeEndpoint(readEp)}, write=${describeEndpoint(writeEp)}")

            val conn = openAndClaim(dev, intf)
            if (conn == null) {
                logLine("USB claim failed. If kernel log shows CLAIMINTERFACE ret=-16, the interface is busy.")
                logLine("Try: force-stop/uninstall original UTCOMP app, reconnect the USB device, then press USB connect again.")
                return
            }

            device = dev
            usbInterface = intf
            readEndpoint = readEp
            writeEndpoint = writeEp
            connection = conn
            running = true
            logLine("USB connected")
            startReadLoop()
            startWriteLoop()
        } catch (t: Throwable) {
            logLine("USB connect failed: ${t.stackTraceToString()}")
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
        txQueue.offer(packet)
        logLine("queued ${packet}")
    }

    fun write(packet: TransmitterPacket) {
        TransmitterPacketParser.toUsbPackets(packet).forEach { write(it) }
    }

    private fun startReadLoop() {
        val conn = connection ?: return
        val ep = readEndpoint ?: return
        thread(name = "utcomp-usb-read", isDaemon = true) {
            val buf = ByteArray(UsbPacket.REPORT_SIZE)
            while (running) {
                try {
                    buf.fill(0)
                    val read = conn.bulkTransfer(ep, buf, ep.maxPacketSize.coerceAtMost(UsbPacket.REPORT_SIZE), 100)
                    if (read > 0) {
                        val report = buf.copyOf(UsbPacket.REPORT_SIZE)
                        logLine("USB raw[$read]: ${report.hex(64)}")
                        val usb = UsbPacket.parse(report)
                        logLine(usb.toString())
                        val txp = TransmitterPacketParser.fromUsb(usb)
                        if (txp != null) {
                            logLine(txp.toString())
                            val decodedLines = UtcompDecoder.apply(txp)
                            decodedLines.forEach { decoded -> logLine(decoded) }
                            if (decodedLines.isNotEmpty()) {
                                onDecodedSnapshot(UtcompDecoder.snapshot.copy())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (running) logLine("USB read failed: ${t.stackTraceToString()}")
                    break
                }
            }
            logLine("USB read loop stopped")
        }
    }

    private fun startWriteLoop() {
        val conn = connection ?: return
        val ep = writeEndpoint ?: return
        thread(name = "utcomp-usb-write", isDaemon = true) {
            while (running) {
                try {
                    val packet = txQueue.take()
                    val bytes = packet.toReport()
                    val written = conn.bulkTransfer(ep, bytes, bytes.size, 100)
                    logLine("USB write[$written]: ${bytes.hex(64)}")
                    if (written < 0) close()
                } catch (t: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (running) logLine("USB write failed: ${t.stackTraceToString()}")
                    break
                }
            }
            logLine("USB write loop stopped")
        }
    }

    override fun close() {
        val hadConnection = connection != null
        running = false
        txQueue.clear()
        runCatching { connection?.releaseInterface(usbInterface) }
        runCatching { connection?.close() }
        connection = null
        usbInterface = null
        readEndpoint = null
        writeEndpoint = null
        if (hadConnection) logLine("USB closed")
    }

    private fun findEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.direction == direction) return ep
        }
        return null
    }

    private fun logInterfaces(dev: UsbDevice) {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            logLine("  interface[$i]: class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}")
            for (e in 0 until intf.endpointCount) {
                logLine("    endpoint[$e]: ${describeEndpoint(intf.getEndpoint(e))}")
            }
        }
    }

    private fun describeDevice(dev: UsbDevice): String =
        "name=${dev.deviceName} vid=${dev.vendorId} pid=${dev.productId} product=${dev.productName} manufacturer=${dev.manufacturerName} interfaces=${dev.interfaceCount}"

    private fun describeEndpoint(ep: UsbEndpoint): String =
        "addr=${ep.address} dir=${directionName(ep.direction)} type=${ep.type} max=${ep.maxPacketSize}"

    private fun directionName(direction: Int): String =
        when (direction) {
            android.hardware.usb.UsbConstants.USB_DIR_IN -> "IN"
            android.hardware.usb.UsbConstants.USB_DIR_OUT -> "OUT"
            else -> direction.toString()
        }

    private fun logLine(line: String) {
        uiLog(line)
    }
}
