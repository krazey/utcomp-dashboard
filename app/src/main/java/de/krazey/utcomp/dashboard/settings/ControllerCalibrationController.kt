package de.krazey.utcomp.dashboard.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.protocol.TransmitterPacket
import de.krazey.utcomp.dashboard.utcomp.ControllerCalibration
import de.krazey.utcomp.dashboard.utcomp.ControllerCalibrationCodec
import de.krazey.utcomp.dashboard.utcomp.LinearCalibration
import de.krazey.utcomp.dashboard.utcomp.SettingChoice
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import java.util.Locale

/** Full-screen, USB-backed editor for verified UTCOMP sensor settings. */
internal class ControllerCalibrationController(
    private val activity: Activity,
    private val isUsbConnected: () -> Boolean,
    private val ensureUsbConnection: () -> Unit,
    private val requestPacket: (Int) -> Unit,
    private val sendPacket: (TransmitterPacket) -> Unit,
    private val onPageVisibilityChanged: (Boolean) -> Unit,
    private val appendLog: (String) -> Unit,
) {
    private enum class Operation { IDLE, READING, WRITING, VERIFYING }

    private data class VerificationTarget(
        val expected: ByteArray,
        val offsets: Set<Int>,
    )

    private data class RollbackBytes(
        val byPid: Map<Int, Map<Int, Byte>>,
    )

    private data class ChoiceEditor(
        val name: String,
        val choices: List<SettingChoice>,
        val button: Button,
        var value: Int = 0,
    )

    private data class NtcEditors(
        val source: TextView,
        val r25: EditText,
        val beta: EditText,
        val correction: EditText,
    )

    private companion object {
        const val READ_TIMEOUT_MS = 4_000L
        const val WRITE_SETTLE_MS = 550L
        const val VERIFY_TIMEOUT_MS = 4_000L
        const val LIVE_POLL_MS = 350L
        const val DIAGNOSTIC_REQUEST_SPACING_MS = 80L

        // Read-only discovery packets. They are deliberately kept outside the codec's
        // required/writable PID sets until their layouts have been verified.
        val SUPPLEMENTAL_DIAGNOSTIC_PIDS: List<Int> = listOf(
            TransmitterConstants.UtcompPid.VSS_SETTINGS,
            TransmitterConstants.UtcompPid.CONSUMPTION_SETTINGS,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS2,
            TransmitterConstants.UtcompPid.GENERAL_SETTINGS1,
            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS1,
            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS2,
            TransmitterConstants.UtcompPid.USERSCREEN_SETTINGS,
            TransmitterConstants.UtcompPid.VREF_SETTINGS,
        )

        val BORDER_COLOR: Int = Color.rgb(38, 78, 104)
        val PANEL_COLOR: Int = Color.rgb(15, 18, 24)
        val SECONDARY_TEXT: Int = Color.rgb(170, 180, 194)
        val GOOD_COLOR: Int = Color.rgb(124, 214, 158)
        val WARNING_COLOR: Int = Color.rgb(255, 190, 96)
        val ERROR_COLOR: Int = Color.rgb(255, 112, 112)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: View
    private lateinit var statusText: TextView
    private lateinit var afrLive: TextView
    private lateinit var boostLive: TextView
    private lateinit var oilPressureLive: TextView
    private lateinit var oilTemperatureLive: TextView
    private lateinit var vrefLive: TextView
    private lateinit var refreshButton: Button
    private lateinit var writeButton: Button
    private lateinit var undoButton: Button

    private lateinit var afrA: EditText
    private lateinit var afrB: EditText
    private lateinit var boostA: EditText
    private lateinit var boostB: EditText
    private lateinit var oilPressureA: EditText
    private lateinit var oilPressureB: EditText
    private val editors = mutableListOf<EditText>()
    private val analogModeEditors = mutableListOf<ChoiceEditor>()
    private val temperatureRoleEditors = mutableListOf<ChoiceEditor>()
    private val ntcEditors = mutableListOf<NtcEditors>()
    private val analogAssignmentLive = mutableListOf<TextView>()
    private val temperatureAssignmentLive = mutableListOf<TextView>()

    private var operation = Operation.IDLE
    @Volatile
    private var visible = false
    private var populatingEditors = false
    private var loadedCalibration: ControllerCalibration? = null
    private var originalPayloads: Map<Int, ByteArray> = emptyMap()
    private val receivedPayloads = linkedMapOf<Int, ByteArray>()
    private val loggedDiagnosticPids = mutableSetOf<Int>()
    private val pendingVerification = linkedMapOf<Int, VerificationTarget>()
    private val verificationFailures = mutableListOf<String>()
    private var pendingRollbackCandidate: RollbackBytes? = null
    private var lastRollback: RollbackBytes? = null
    private var pendingWriteIsRollback = false
    private var previousRequestedOrientation: Int? = null
    private var liveAfr = Float.NaN
    private var liveBoost = Float.NaN
    private var liveOilPressure = Float.NaN
    private var liveOilTemperature = Float.NaN
    private var liveAfr2 = Float.NaN
    private var liveFuelPressure = Float.NaN
    private var liveGear = 0
    private var liveVrefMillivolts = 0
    private val liveAdcVolts = FloatArray(8) { Float.NaN }
    private val livePhysicalTemperatures = FloatArray(7) { Float.NaN }
    private val liveEgt = IntArray(6)

    private val livePoll = object : Runnable {
        override fun run() {
            if (!visible || !isUsbConnected()) return
            if (operation == Operation.IDLE && loadedCalibration != null) {
                requestPacket(TransmitterConstants.UtcompPid.GENERAL_DATA1)
                requestPacket(TransmitterConstants.UtcompPid.GENERAL_DATA2)
                requestPacket(TransmitterConstants.UtcompPid.TEMPERATURES_DATA)
            }
            mainHandler.postDelayed(this, LIVE_POLL_MS)
        }
    }

    private val readTimeout = Runnable {
        if (operation != Operation.READING) return@Runnable
        operation = Operation.IDLE
        loadedCalibration = null
        val missing = missingReadPids()
        val missingText = missing.joinToString { describePid(it) }
        setStatus(
            "Sensor-settings read timed out; missing $missingText. Check USB and tap Refresh.",
            ERROR_COLOR,
        )
        appendLog(
            "Controller calibration read timed out; received=" +
                receivedPayloads.keys.joinToString { describePid(it) } +
                " missing=$missingText",
        )
        updateEnabledState()
    }

    private val beginVerification = Runnable {
        if (operation != Operation.WRITING || !visible) return@Runnable
        if (!isUsbConnected()) {
            failAmbiguousWrite("USB disconnected before read-back verification")
            return@Runnable
        }

        operation = Operation.VERIFYING
        receivedPayloads.clear()
        originalPayloads.forEach { (pid, payload) ->
            receivedPayloads[pid] = payload.copyOf()
        }
        setStatus(
            "Write sent once. Reading back 0/${pendingVerification.size} changed packets…",
            WARNING_COLOR,
        )
        updateEnabledState()
        requestNextVerificationPacket()
        mainHandler.removeCallbacks(verifyTimeout)
        mainHandler.postDelayed(verifyTimeout, VERIFY_TIMEOUT_MS)
    }

    private val verifyTimeout = Runnable {
        if (operation != Operation.VERIFYING) return@Runnable
        val missing = pendingVerification.keys.joinToString { "0x%04X".format(it) }
        failAmbiguousWrite("Read-back verification timed out; missing $missing")
    }

    fun createView(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(Color.rgb(10, 12, 16))
            visibility = View.GONE
        }

        page.addView(createHeader())

        statusText = TextView(activity).apply {
            textSize = 13f
            setTextColor(SECONDARY_TEXT)
            setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
            text = "Open this page with UTCOMP connected to read its calibration."
        }
        page.addView(statusText)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2f), 0, dp(2f), dp(8f))
        }

        content.addView(infoPanel())
        content.addView(analogAssignmentsSection(), panelLayoutParams())
        content.addView(temperatureAssignmentsSection(), panelLayoutParams())

        afrLive = liveValueText()
        afrA = numberEditor("2")
        afrB = numberEditor("10")
        content.addView(
            linearSection(
                title = "AFR",
                description = "AFR = a × input voltage + b",
                live = afrLive,
                firstLabel = "a",
                first = afrA,
                secondLabel = "b",
                second = afrB,
            ),
            panelLayoutParams(),
        )

        boostLive = liveValueText()
        boostA = numberEditor("2")
        boostB = numberEditor("-1")
        content.addView(
            linearSection(
                title = "Boost pressure",
                description = "bar = a × input voltage + b",
                live = boostLive,
                firstLabel = "a",
                first = boostA,
                secondLabel = "b",
                second = boostB,
            ),
            panelLayoutParams(),
        )

        oilPressureLive = liveValueText()
        oilPressureA = numberEditor("2.5")
        oilPressureB = numberEditor("-1.25")
        content.addView(
            linearSection(
                title = "Oil pressure",
                description = "bar = a × input voltage + b",
                live = oilPressureLive,
                firstLabel = "a",
                first = oilPressureA,
                secondLabel = "b",
                second = oilPressureB,
            ),
            panelLayoutParams(),
        )

        oilTemperatureLive = liveValueText()
        content.addView(ntcProfilesSection(), panelLayoutParams())

        vrefLive = liveValueText()
        content.addView(vrefSection(), panelLayoutParams())

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        page.addView(createActionRow())

        root = page
        updateEnabledState()
        return page
    }

    fun show() {
        check(::root.isInitialized) { "Calibration view has not been created" }
        if (visible) return

        visible = true
        lastRollback = null
        clearLiveValues()
        previousRequestedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        root.visibility = View.VISIBLE
        onPageVisibilityChanged(true)
        ensureUsbConnection()
        if (isUsbConnected()) {
            readFromController()
        } else {
            setStatus("Waiting for UTCOMP USB connection…", WARNING_COLOR)
            updateEnabledState()
        }
    }

    fun close() {
        if (!visible) return
        if (operation == Operation.WRITING || operation == Operation.VERIFYING) {
            Toast.makeText(
                activity,
                "Wait for controller verification to finish",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        cancelTimeouts()
        stopLivePolling()
        visible = false
        operation = Operation.IDLE
        loadedCalibration = null
        originalPayloads = emptyMap()
        receivedPayloads.clear()
        lastRollback = null
        root.visibility = View.GONE
        onPageVisibilityChanged(false)
        previousRequestedOrientation?.let { activity.requestedOrientation = it }
        previousRequestedOrientation = null
    }

    fun isVisible(): Boolean = visible

    fun destroy() {
        visible = false
        cancelTimeouts()
        stopLivePolling()
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!visible) return
        activity.runOnUiThread {
            if (!visible) return@runOnUiThread
            if (connected) {
                if (operation == Operation.IDLE && loadedCalibration == null) {
                    readFromController()
                }
            } else {
                cancelTimeouts()
                stopLivePolling()
                operation = Operation.IDLE
                loadedCalibration = null
                originalPayloads = emptyMap()
                receivedPayloads.clear()
                pendingVerification.clear()
                lastRollback = null
                clearLiveValues()
                setStatus("UTCOMP disconnected. Reconnect it and tap Refresh.", ERROR_COLOR)
                updateEnabledState()
            }
        }
    }

    fun onPacketReceived(packet: TransmitterPacket) {
        if (
            !visible ||
            (packet.pid !in SUPPLEMENTAL_DIAGNOSTIC_PIDS &&
                !ControllerCalibrationCodec.accepts(packet.pid))
        ) return
        if (packet.cmd != TransmitterConstants.Command.TRANSFER_DATA) return
        if (packet.source != TransmitterConstants.Source.DEVICE) return
        if (packet.data.size != 48) return

        val pid = packet.pid
        val payload = packet.data.copyOf()
        activity.runOnUiThread {
            if (!visible) return@runOnUiThread
            if (pid in SUPPLEMENTAL_DIAGNOSTIC_PIDS) {
                if (loggedDiagnosticPids.add(pid)) {
                    appendLog(
                        "Controller diagnostic received ${describePid(pid)} " +
                            "bytes=${payload.toCalibrationHex()}",
                    )
                }
            } else {
                receivePayload(pid, payload)
            }
        }
    }

    fun offerSnapshot(snapshot: UtcompDataSnapshot, sourcePid: Int) {
        if (!visible) return

        when (sourcePid) {
            TransmitterConstants.UtcompPid.GENERAL_DATA1 -> {
                val adcVolts = floatArrayOf(
                    snapshot.adcInValCh0,
                    snapshot.adcInValCh1,
                    snapshot.adcInValCh2,
                    snapshot.adcInValCh3,
                    snapshot.adcInValCh4,
                    snapshot.adcInValCh5,
                    snapshot.adcInValCh6,
                    snapshot.adcInValCh7,
                )
                val vrefMillivolts = snapshot.vref
                val gear = snapshot.gearNo
                activity.runOnUiThread {
                    if (!canShowLiveValues()) return@runOnUiThread
                    adcVolts.copyInto(liveAdcVolts)
                    liveVrefMillivolts = vrefMillivolts
                    liveGear = gear
                    updateLiveReadouts()
                }
            }

            TransmitterConstants.UtcompPid.GENERAL_DATA2 -> {
                val afr = snapshot.afr1
                val afr2 = snapshot.afr2
                val boost = snapshot.bar1
                val oilPressure = snapshot.bar2
                val fuelPressure = snapshot.bar3
                val ntc3 = snapshot.temperatureNtc3
                val egt = intArrayOf(
                    snapshot.egt1,
                    snapshot.egt2,
                    snapshot.egt3,
                    snapshot.egt4,
                    snapshot.egt5,
                    snapshot.egt6,
                )
                activity.runOnUiThread {
                    if (!canShowLiveValues()) return@runOnUiThread
                    liveAfr = afr
                    liveAfr2 = afr2
                    liveBoost = boost
                    liveOilPressure = oilPressure
                    liveFuelPressure = fuelPressure
                    livePhysicalTemperatures[6] = ntc3
                    egt.copyInto(liveEgt)
                    updateLiveReadouts()
                }
            }

            TransmitterConstants.UtcompPid.TEMPERATURES_DATA -> {
                val oilTemperature = snapshot.temperatureOil
                val physical = floatArrayOf(
                    snapshot.temperatureDsA,
                    snapshot.temperatureDsB,
                    snapshot.temperatureDsC,
                    snapshot.temperatureDsD,
                    snapshot.temperatureNtc1,
                    snapshot.temperatureNtc2,
                )
                activity.runOnUiThread {
                    if (!canShowLiveValues()) return@runOnUiThread
                    liveOilTemperature = oilTemperature
                    physical.copyInto(livePhysicalTemperatures)
                    updateLiveReadouts()
                }
            }
        }
    }

    private fun readFromController() {
        if (!visible) return
        if (!isUsbConnected()) {
            ensureUsbConnection()
            setStatus("Waiting for UTCOMP USB connection…", WARNING_COLOR)
            return
        }

        cancelTimeouts()
        stopLivePolling()
        operation = Operation.READING
        loadedCalibration = null
        clearLiveValues()
        originalPayloads = emptyMap()
        receivedPayloads.clear()
        loggedDiagnosticPids.clear()
        lastRollback = null
        pendingVerification.clear()
        verificationFailures.clear()
        setStatus(
            "Reading 0/${ControllerCalibrationCodec.requiredPids.size} sensor-settings packets…",
            WARNING_COLOR,
        )
        updateEnabledState()
        requestNextReadPacket()
        mainHandler.postDelayed(readTimeout, READ_TIMEOUT_MS)
        appendLog("Controller calibration read started")
    }

    private fun receivePayload(pid: Int, payload: ByteArray) {
        when (operation) {
            Operation.READING -> receiveReadPayload(pid, payload)
            Operation.VERIFYING -> receiveVerificationPayload(pid, payload)
            Operation.IDLE,
            Operation.WRITING -> Unit
        }
    }

    private fun receiveReadPayload(pid: Int, payload: ByteArray) {
        val firstReceipt = pid !in receivedPayloads
        receivedPayloads[pid] = payload
        val count = ControllerCalibrationCodec.requiredPids.count(receivedPayloads::containsKey)
        setStatus(
            "Reading $count/${ControllerCalibrationCodec.requiredPids.size} sensor-settings packets…",
            WARNING_COLOR,
        )
        if (firstReceipt) {
            appendLog(
                "Controller calibration received ${describePid(pid)} " +
                    "bytes=${payload.toCalibrationHex()}",
            )
        }
        if (count != ControllerCalibrationCodec.requiredPids.size) {
            mainHandler.removeCallbacks(readTimeout)
            requestNextReadPacket()
            mainHandler.postDelayed(readTimeout, READ_TIMEOUT_MS)
            return
        }

        val decoded = ControllerCalibrationCodec.decode(receivedPayloads)
        if (decoded == null) {
            operation = Operation.IDLE
            setStatus("Controller returned incomplete calibration data.", ERROR_COLOR)
            updateEnabledState()
            return
        }

        mainHandler.removeCallbacks(readTimeout)
        operation = Operation.IDLE
        originalPayloads = receivedPayloads.mapValues { it.value.copyOf() }
        loadedCalibration = decoded
        populateEditors(decoded)
        setStatus(
            "Sensor calibration loaded directly from UTCOMP.",
            GOOD_COLOR,
        )
        appendLog("Controller calibration read complete")
        requestSupplementalDiagnostics()
        updateEnabledState()
        startLivePolling()
    }

    private fun receiveVerificationPayload(pid: Int, payload: ByteArray) {
        val target = pendingVerification.remove(pid) ?: return
        receivedPayloads[pid] = payload

        val badOffsets = target.offsets.filter { offset ->
            payload.getOrNull(offset) != target.expected.getOrNull(offset)
        }
        if (badOffsets.isNotEmpty()) {
            verificationFailures += "pid=0x%04X bytes=%s".format(
                pid,
                badOffsets.joinToString(),
            )
        }

        setStatus(
            "Reading back ${receivedVerificationCount()}/$verificationTargetCount changed packets…",
            WARNING_COLOR,
        )
        if (pendingVerification.isNotEmpty()) {
            mainHandler.removeCallbacks(verifyTimeout)
            requestNextVerificationPacket()
            mainHandler.postDelayed(verifyTimeout, VERIFY_TIMEOUT_MS)
            return
        }

        mainHandler.removeCallbacks(verifyTimeout)
        if (verificationFailures.isNotEmpty()) {
            failAmbiguousWrite(
                "Controller read-back differs at ${verificationFailures.joinToString("; ")}",
            )
            return
        }

        val decoded = ControllerCalibrationCodec.decode(receivedPayloads)
        if (decoded == null) {
            failAmbiguousWrite("Read-back packets could not be decoded")
            return
        }

        originalPayloads = receivedPayloads.mapValues { it.value.copyOf() }
        loadedCalibration = decoded
        populateEditors(decoded)
        operation = Operation.IDLE
        if (pendingWriteIsRollback) {
            lastRollback = null
            setStatus("Previous calibration restored and verified.", GOOD_COLOR)
            appendLog("Controller calibration rollback verified")
        } else {
            lastRollback = pendingRollbackCandidate
            setStatus("Calibration written once and verified by read-back.", GOOD_COLOR)
            appendLog("Controller calibration write verified")
        }
        pendingRollbackCandidate = null
        pendingWriteIsRollback = false
        pendingVerification.clear()
        updateEnabledState()
        startLivePolling()
    }

    private var verificationTargetCount = 0

    private fun receivedVerificationCount(): Int =
        (verificationTargetCount - pendingVerification.size).coerceAtLeast(0)

    private fun requestWrite() {
        val current = loadedCalibration ?: return
        val edited = runCatching { readEditors(current) }
            .getOrElse { error ->
                setStatus(error.message ?: "Invalid calibration value", ERROR_COLOR)
                Toast.makeText(
                    activity,
                    error.message ?: "Invalid calibration value",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }

        val encoded = ControllerCalibrationCodec.encode(originalPayloads, edited)
        val changed = ControllerCalibrationCodec.changedPayloads(originalPayloads, encoded)
        if (changed.isEmpty()) {
            setStatus("No calibration values have changed.", SECONDARY_TEXT)
            return
        }

        val summary = describeChanges(current, edited)
        AlertDialog.Builder(activity)
            .setTitle("Write controller calibration?")
            .setMessage(
                "Use ignition on with the engine stopped. Keep USB and controller power connected.\n\n" +
                    summary.joinToString(separator = "\n") { "• $it" } +
                    "\n\nEach changed packet is sent once, committed, then read back.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write and verify") { _, _ ->
                executeWrite(changed, isRollback = false)
            }
            .show()
    }

    private fun requestRollback() {
        val rollback = lastRollback ?: return
        val restored = linkedMapOf<Int, ByteArray>()
        rollback.byPid.forEach { (pid, oldBytes) ->
            val current = originalPayloads[pid]?.copyOf() ?: return@forEach
            oldBytes.forEach { (offset, value) -> current[offset] = value }
            if (!current.contentEquals(originalPayloads[pid])) restored[pid] = current
        }
        if (restored.isEmpty()) {
            lastRollback = null
            updateEnabledState()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Restore previous calibration?")
            .setMessage(
                "Only calibration bytes changed by the last verified write will be restored. " +
                    "The rollback is also committed once and verified by read-back.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore and verify") { _, _ ->
                executeWrite(restored, isRollback = true)
            }
            .show()
    }

    private fun executeWrite(changed: Map<Int, ByteArray>, isRollback: Boolean) {
        if (!isUsbConnected()) {
            setStatus("UTCOMP is not connected; nothing was written.", ERROR_COLOR)
            return
        }

        cancelTimeouts()
        stopLivePolling()
        pendingVerification.clear()
        verificationFailures.clear()
        val rollbackByPid = linkedMapOf<Int, Map<Int, Byte>>()
        changed.forEach { (pid, expected) ->
            val before = originalPayloads.getValue(pid)
            val offsets = ControllerCalibrationCodec.changedByteOffsets(before, expected)
            check(offsets.isNotEmpty())
            pendingVerification[pid] = VerificationTarget(expected.copyOf(), offsets)
            rollbackByPid[pid] = offsets.associateWith { before[it] }
        }
        verificationTargetCount = pendingVerification.size
        pendingWriteIsRollback = isRollback
        pendingRollbackCandidate = if (isRollback) null else RollbackBytes(rollbackByPid)

        operation = Operation.WRITING
        setStatus(
            "Sending ${changed.size} changed packet(s) once, then committing…",
            WARNING_COLOR,
        )
        updateEnabledState()

        ControllerCalibrationCodec.writablePids.forEach { pid ->
            changed[pid]?.let { payload ->
                sendPacket(TransmitterPacket.transfer(pid, payload))
            }
        }
        sendPacket(
            TransmitterPacket.transfer(
                TransmitterConstants.UtcompPid.SETTINGS_STOP,
                ByteArray(48),
            ),
        )
        appendLog(
            "Controller calibration ${if (isRollback) "rollback" else "write"} sent " +
                "once for ${changed.keys.joinToString { "0x%04X".format(it) }}",
        )
        mainHandler.postDelayed(beginVerification, WRITE_SETTLE_MS)
    }

    private fun failAmbiguousWrite(message: String) {
        cancelTimeouts()
        operation = Operation.IDLE
        loadedCalibration = null
        originalPayloads = emptyMap()
        receivedPayloads.clear()
        pendingVerification.clear()
        verificationFailures.clear()
        pendingRollbackCandidate = null
        pendingWriteIsRollback = false
        lastRollback = null
        clearLiveValues()
        setStatus("$message. Refresh before another write.", ERROR_COLOR)
        appendLog("Controller calibration verification failed: $message")
        updateEnabledState()
    }

    private fun readEditors(base: ControllerCalibration): ControllerCalibration {
        val analogModes = analogModeEditors.map(ChoiceEditor::value)
        val temperatureRoles = temperatureRoleEditors.map(ChoiceEditor::value)
        requireUniqueAssignments(
            base.analogInputModes,
            analogModes,
            ControllerCalibration.PHYSICAL_ANALOG_INPUTS,
            setOf(
                ControllerCalibration.ANALOG_MODE_DISABLED,
                ControllerCalibration.ANALOG_MODE_OSCILLOSCOPE,
            ),
            { value -> ControllerCalibration.analogModeLabel(value) },
        )
        requireUniqueAssignments(
            base.temperatureRoles,
            temperatureRoles,
            ControllerCalibration.TEMPERATURE_INPUTS,
            setOf(ControllerCalibration.TEMPERATURE_ROLE_DISABLED),
            { value -> ControllerCalibration.temperatureRoleLabel(value) },
        )

        val editedNtc = base.ntc.mapIndexed { index, currentNtc ->
            val fields = ntcEditors[index]
            currentNtc.copy(
                r25Ohms = parseFloat(fields.r25, "NTC${index + 1} R25")
                    .requireRange(100f, 1_000_000f, "NTC${index + 1} R25"),
                betaKelvin = parseFloat(fields.beta, "NTC${index + 1} beta")
                    .requireRange(100f, 10_000f, "NTC${index + 1} beta"),
                correctionCelsius = parseFloat(
                    fields.correction,
                    "NTC${index + 1} correction",
                ).requireRange(-100f, 100f, "NTC${index + 1} correction"),
            )
        }

        return base.copy(
            afr = LinearCalibration(
                parseLinear(afrA, "AFR a"),
                parseLinear(afrB, "AFR b"),
            ),
            boost = LinearCalibration(
                parseLinear(boostA, "Boost a"),
                parseLinear(boostB, "Boost b"),
            ),
            oilPressure = LinearCalibration(
                parseLinear(oilPressureA, "Oil pressure a"),
                parseLinear(oilPressureB, "Oil pressure b"),
            ),
            ntc = editedNtc,
            analogInputModes = analogModes,
        ).withTemperatureRoles(temperatureRoles)
    }

    private fun requireUniqueAssignments(
        originalValues: List<Int>,
        values: List<Int>,
        inputs: List<String>,
        repeatable: Set<Int>,
        label: (Int) -> String,
    ) {
        values.withIndex()
            .filter { it.value !in repeatable }
            .groupBy { it.value }
            .values
            .firstOrNull { duplicates ->
                if (duplicates.size <= 1) return@firstOrNull false
                val value = duplicates.first().value
                val originalIndices = originalValues.withIndex()
                    .filter { it.value == value }
                    .map { it.index }
                duplicates.map { it.index } != originalIndices
            }
            ?.let { duplicates ->
                val value = duplicates.first().value
                val sources = duplicates.joinToString { inputs[it.index] }
                throw IllegalArgumentException("${label(value)} is assigned more than once: $sources")
            }
    }

    private fun parseLinear(editor: EditText, name: String): Float =
        parseFloat(editor, name).requireRange(-10_000f, 10_000f, name)

    private fun parseFloat(editor: EditText, name: String): Float {
        val value = editor.text.toString().trim().replace(',', '.').toFloatOrNull()
            ?: throw IllegalArgumentException("$name is not a valid number")
        require(value.isFinite()) { "$name must be finite" }
        return value
    }

    private fun Float.requireRange(min: Float, max: Float, name: String): Float {
        require(this in min..max) { "$name must be between ${format(min)} and ${format(max)}" }
        return this
    }

    private fun populateEditors(calibration: ControllerCalibration) {
        populatingEditors = true
        try {
            afrA.setText(format(calibration.afr.a))
            afrB.setText(format(calibration.afr.b))
            boostA.setText(format(calibration.boost.a))
            boostB.setText(format(calibration.boost.b))
            oilPressureA.setText(format(calibration.oilPressure.a))
            oilPressureB.setText(format(calibration.oilPressure.b))
            analogModeEditors.forEachIndexed { index, editor ->
                editor.value = calibration.analogInputModes[index]
                updateChoiceButton(editor)
            }
            temperatureRoleEditors.forEachIndexed { index, editor ->
                editor.value = calibration.temperatureRoles[index]
                updateChoiceButton(editor)
            }
            ntcEditors.forEachIndexed { index, fields ->
                val profile = calibration.ntc[index]
                fields.r25.setText(format(profile.r25Ohms))
                fields.beta.setText(format(profile.betaKelvin))
                fields.correction.setText(format(profile.correctionCelsius))
                fields.source.text = buildString {
                    append("NTC${index + 1} · ")
                    append(ControllerCalibration.temperatureRoleLabel(profile.role))
                    append(" · physical input: ")
                    append(calibration.physicalInputForNtc(index) ?: "not assigned")
                }
            }
            updateLiveReadouts()
        } finally {
            populatingEditors = false
        }
    }

    private fun describeChanges(
        before: ControllerCalibration,
        after: ControllerCalibration,
    ): List<String> = buildList {
        addLinearChanges("AFR", before.afr, after.afr)
        addLinearChanges("Boost", before.boost, after.boost)
        addLinearChanges("Oil pressure", before.oilPressure, after.oilPressure)
        before.analogInputModes.indices.forEach { index ->
            val old = before.analogInputModes[index]
            val new = after.analogInputModes[index]
            if (old != new) {
                add(
                    "${ControllerCalibration.PHYSICAL_ANALOG_INPUTS[index]}: " +
                        "${ControllerCalibration.analogModeLabel(old)} → " +
                        ControllerCalibration.analogModeLabel(new),
                )
            }
        }
        before.temperatureRoles.indices.forEach { index ->
            val old = before.temperatureRoles[index]
            val new = after.temperatureRoles[index]
            if (old != new) {
                add(
                    "${ControllerCalibration.TEMPERATURE_INPUTS[index]}: " +
                        "${ControllerCalibration.temperatureRoleLabel(old)} → " +
                        ControllerCalibration.temperatureRoleLabel(new),
                )
            }
        }
        before.ntc.indices.forEach { index ->
            val old = before.ntc[index]
            val new = after.ntc[index]
            if (!sameFloat(old.r25Ohms, new.r25Ohms)) {
                add("NTC${index + 1} R25: ${format(old.r25Ohms)} → ${format(new.r25Ohms)} Ω")
            }
            if (!sameFloat(old.betaKelvin, new.betaKelvin)) {
                add("NTC${index + 1} beta: ${format(old.betaKelvin)} → ${format(new.betaKelvin)} K")
            }
            if (!sameFloat(old.correctionCelsius, new.correctionCelsius)) {
                add(
                    "NTC${index + 1} correction: ${format(old.correctionCelsius)} → " +
                        "${format(new.correctionCelsius)} °C",
                )
            }
        }
    }

    private fun MutableList<String>.addLinearChanges(
        name: String,
        before: LinearCalibration,
        after: LinearCalibration,
    ) {
        if (!sameFloat(before.a, after.a)) {
            add("$name a: ${format(before.a)} → ${format(after.a)}")
        }
        if (!sameFloat(before.b, after.b)) {
            add("$name b: ${format(before.b)} → ${format(after.b)}")
        }
    }

    private fun sameFloat(first: Float, second: Float): Boolean =
        first.toRawBits() == second.toRawBits()

    private fun createHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            actionButton("‹ DASHBOARD") { close() },
            LinearLayout.LayoutParams(dp(132f), dp(52f)).apply {
                setMargins(0, 0, dp(10f), 0)
            },
        )
        addView(
            TextView(activity).apply {
                text = "Controller sensor setup"
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun infoPanel(): View = TextView(activity).apply {
        text =
            "Values are read from UTCOMP PRO, not app defaults. Live sensor values and raw " +
                "input voltages refresh while idle; polling pauses during settings operations. " +
                "This page configures sensor inputs and calibration only; UTCOMP OLED screens " +
                "are not changed. A write sends no automatic retry, commits once, and must " +
                "pass read-back verification."
        textSize = 12f
        setTextColor(SECONDARY_TEXT)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = roundedBackground(Color.rgb(8, 10, 14), 14f)
        layoutParams = panelLayoutParams()
    }

    private fun linearSection(
        title: String,
        description: String,
        live: TextView,
        firstLabel: String,
        first: EditText,
        secondLabel: String,
        second: EditText,
    ): View = section(title, description).apply {
        addView(live)
        addView(editorRow(firstLabel, first, secondLabel, second))
    }

    private fun analogAssignmentsSection(): View = section(
        title = "Analog input assignments",
        description =
            "Link each physical input to its UTCOMP sensor function. The live line shows " +
                "the raw voltage and, where available, the currently decoded value.",
    ).apply {
        ControllerCalibration.PHYSICAL_ANALOG_INPUTS.forEachIndexed { index, input ->
            val live = assignmentLiveText()
            analogAssignmentLive += live
            val editor = choiceEditor(
                name = input,
                choices = ControllerCalibration.ANALOG_MODE_CHOICES,
            )
            analogModeEditors += editor
            addView(assignmentRow(input, live, editor.button))
        }
    }

    private fun temperatureAssignmentsSection(): View = section(
        title = "Temperature assignments",
        description =
            "Assign every DS18B20 or NTC source to Outside, Inside, Engine, Oil, User 1, " +
                "User 2, or Disabled.",
    ).apply {
        ControllerCalibration.TEMPERATURE_INPUTS.forEach { input ->
            val live = assignmentLiveText()
            temperatureAssignmentLive += live
            val editor = choiceEditor(
                name = input,
                choices = ControllerCalibration.TEMPERATURE_ROLE_CHOICES,
            )
            temperatureRoleEditors += editor
            addView(assignmentRow(input, live, editor.button))
        }
    }

    private fun ntcProfilesSection(): View = section(
        title = "NTC sensor profiles",
        description =
            "Each profile is independent. R25, beta, and temperature correction are read " +
                "from and written to the controller.",
    ).apply {
        addView(oilTemperatureLive)
        repeat(3) { index ->
            val source = TextView(activity).apply {
                text = "NTC${index + 1} · waiting for controller settings"
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, if (index == 0) dp(4f) else dp(12f), 0, dp(4f))
            }
            val r25 = numberEditor("10000")
            val beta = numberEditor("3500")
            val correction = numberEditor("0")
            ntcEditors += NtcEditors(source, r25, beta, correction)
            addView(source)
            addView(editorRow("R25 (Ω)", r25, "Beta (K)", beta))
            addView(editorRow("Correction (°C)", correction, null, null))
        }
    }

    private fun assignmentRow(label: String, live: TextView, button: Button): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = label
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(live)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f).apply {
                    setMargins(0, 0, dp(8f), 0)
                },
            )
            addView(
                button,
                LinearLayout.LayoutParams(0, dp(50f), 0.58f),
            )
        }

    private fun assignmentLiveText(): TextView = TextView(activity).apply {
        text = "LIVE · waiting"
        textSize = 11.5f
        setTextColor(GOOD_COLOR)
        setPadding(0, dp(2f), 0, 0)
    }

    private fun choiceEditor(name: String, choices: List<SettingChoice>): ChoiceEditor {
        lateinit var editor: ChoiceEditor
        val button = actionButton("Waiting for controller") {
            if (operation == Operation.IDLE && loadedCalibration != null) {
                showChoiceDialog(editor)
            }
        }
        editor = ChoiceEditor(name, choices, button)
        return editor
    }

    private fun showChoiceDialog(editor: ChoiceEditor) {
        val labels = editor.choices.map(SettingChoice::label).toTypedArray()
        val selected = editor.choices.indexOfFirst { it.value == editor.value }
        AlertDialog.Builder(activity)
            .setTitle(editor.name)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val choice = editor.choices[which]
                editor.value = choice.value
                updateChoiceButton(editor)
                dialog.dismiss()
                if (!populatingEditors) {
                    setStatus("Edited locally · not written to UTCOMP", WARNING_COLOR)
                    updateLiveReadouts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateChoiceButton(editor: ChoiceEditor) {
        editor.button.text = editor.choices.firstOrNull { it.value == editor.value }?.label
            ?: "Unknown (${editor.value})"
    }

    private fun vrefSection(): View = section(
        title = "ADC reference voltage · read only",
        description =
            "Factory-calibrated controller reference reported by live data; CAL never writes it.",
    ).apply {
        addView(vrefLive)
    }

    private fun liveValueText(): TextView = TextView(activity).apply {
        text = "LIVE · waiting for controller data"
        textSize = 13f
        setTextColor(GOOD_COLOR)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(1f), 0, dp(7f))
    }

    private fun section(title: String, description: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = roundedBackground(PANEL_COLOR, 14f)
            if (title.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
            }
            if (description.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = description
                    textSize = 11.5f
                    setTextColor(SECONDARY_TEXT)
                    setPadding(0, dp(2f), 0, dp(7f))
                })
            }
        }

    private fun editorRow(
        firstLabel: String,
        first: EditText,
        secondLabel: String?,
        second: EditText?,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(editorColumn(firstLabel, first), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, if (second != null) dp(6f) else 0, 0)
        })
        if (secondLabel != null && second != null) {
            addView(editorColumn(secondLabel, second), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })
        }
    }

    private fun editorColumn(label: String, editor: EditText): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(SECONDARY_TEXT)
                setPadding(dp(3f), 0, 0, dp(2f))
            })
            addView(
                editor,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50f),
                ),
            )
        }

    private fun numberEditor(hintText: String): EditText = createEditor(
        hintText,
        InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED,
    )

    private fun createEditor(hintText: String, input: Int): EditText =
        EditText(activity).apply {
            hint = hintText
            inputType = input
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(104, 112, 124))
            setSelectAllOnFocus(true)
            setPadding(dp(12f), 0, dp(12f), 0)
            background = roundedBackground(Color.BLACK, 12f)
            editors += this
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!populatingEditors && operation == Operation.IDLE && loadedCalibration != null) {
                        setStatus("Edited locally · not written to UTCOMP", WARNING_COLOR)
                    }
                }
            })
        }

    private fun createActionRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8f), 0, 0)

        refreshButton = actionButton("↻ REFRESH") { readFromController() }
        writeButton = actionButton("WRITE + VERIFY") { requestWrite() }
        undoButton = actionButton("UNDO LAST WRITE") { requestRollback() }

        addView(refreshButton, actionLayoutParams())
        addView(writeButton, actionLayoutParams())
        addView(undoButton, actionLayoutParams(last = true))
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minimumHeight = dp(52f)
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK, 14f)
            setOnClickListener { onClick() }
        }

    private fun actionLayoutParams(last: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(54f), 1f).apply {
            setMargins(0, 0, if (last) 0 else dp(7f), 0)
        }

    private fun panelLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(5f), 0, dp(5f)) }

    private fun updateEnabledState() {
        if (!::refreshButton.isInitialized) return
        val idle = operation == Operation.IDLE
        val canEdit = idle && isUsbConnected() && loadedCalibration != null
        editors.forEach { it.isEnabled = canEdit }
        analogModeEditors.forEach { editor ->
            editor.button.isEnabled = canEdit
            setButtonVisual(editor.button)
        }
        temperatureRoleEditors.forEach { editor ->
            editor.button.isEnabled = canEdit
            setButtonVisual(editor.button)
        }
        refreshButton.isEnabled = idle
        writeButton.isEnabled = canEdit
        undoButton.isEnabled = canEdit && lastRollback != null
        setButtonVisual(refreshButton)
        setButtonVisual(writeButton)
        setButtonVisual(undoButton)
    }

    private fun setButtonVisual(button: Button) {
        button.alpha = if (button.isEnabled) 1f else 0.42f
    }

    private fun setStatus(message: String, color: Int) {
        if (!::statusText.isInitialized) return
        statusText.text = message
        statusText.setTextColor(color)
    }

    private fun format(value: Float): String =
        value.toString().removeSuffix(".0")

    private fun formatVolts(millivolts: Int): String =
        String.format(Locale.US, "%.3f", millivolts / 1000f)

    private fun requestNextReadPacket() {
        missingReadPids().firstOrNull()?.let(requestPacket)
    }

    private fun requestNextVerificationPacket() {
        pendingVerification.keys.firstOrNull()?.let(requestPacket)
    }

    private fun requestSupplementalDiagnostics() {
        appendLog(
            "Controller supplemental diagnostic read started pids=" +
                SUPPLEMENTAL_DIAGNOSTIC_PIDS.joinToString { "0x%04X".format(it) },
        )
        SUPPLEMENTAL_DIAGNOSTIC_PIDS.forEachIndexed { index, pid ->
            mainHandler.postDelayed(
                {
                    if (visible && isUsbConnected()) requestPacket(pid)
                },
                index * DIAGNOSTIC_REQUEST_SPACING_MS,
            )
        }
    }

    private fun missingReadPids(): List<Int> =
        ControllerCalibrationCodec.requiredPids.filterNot(receivedPayloads::containsKey)

    private fun describePid(pid: Int): String = when (pid) {
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS -> "temperature settings (0x1002)"
        TransmitterConstants.UtcompPid.VSS_SETTINGS -> "VSS settings (0x1004)"
        TransmitterConstants.UtcompPid.CONSUMPTION_SETTINGS -> "consumption settings (0x1006)"
        TransmitterConstants.UtcompPid.GPIO_SETTINGS -> "input assignments (0x1009)"
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1 -> "analog settings (0x100A)"
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS2 -> "analog settings 2 (0x100B)"
        TransmitterConstants.UtcompPid.GENERAL_SETTINGS1 -> "general settings (0x100C)"
        TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS1 ->
            "analog sampling settings 1 (0x1017)"
        TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS2 ->
            "analog sampling settings 2 (0x1018)"
        TransmitterConstants.UtcompPid.USERSCREEN_SETTINGS -> "user-screen settings (0x1019)"
        TransmitterConstants.UtcompPid.VREF_SETTINGS -> "Vref settings (0x101A)"
        else -> "0x%04X".format(pid)
    }

    private fun ByteArray.toCalibrationHex(): String = joinToString(separator = "") { byte ->
        "%02X".format(Locale.US, byte.toInt() and 0xFF)
    }

    private fun updateLiveReadouts() {
        if (!::afrLive.isInitialized) return
        val calibration = loadedCalibration
        if (calibration == null) {
            listOf(
                afrLive,
                boostLive,
                oilPressureLive,
                oilTemperatureLive,
                vrefLive,
            ).forEach { it.text = "LIVE · waiting for controller data" }
            analogAssignmentLive.forEach { it.text = "LIVE · waiting" }
            temperatureAssignmentLive.forEach { it.text = "LIVE · waiting" }
            return
        }

        analogAssignmentLive.forEachIndexed { index, view ->
            val mode = analogModeEditors.getOrNull(index)?.value
                ?: calibration.analogInputModes[index]
            val raw = liveAdcVolts.getOrElse(index + 1) { Float.NaN }
            view.text = buildString {
                append("LIVE  ")
                append(formatLive(raw, 3))
                append(" V")
                val decoded = if (mode == calibration.analogInputModes[index]) {
                    analogDecodedText(mode)
                } else {
                    "pending write"
                }
                decoded?.let {
                    append(" · ")
                    append(it)
                }
            }
        }
        temperatureAssignmentLive.forEachIndexed { index, view ->
            val pending = temperatureRoleEditors.getOrNull(index)?.value
                ?.takeIf { it != calibration.temperatureRoles[index] }
            view.text = buildString {
                append("LIVE  ${formatLive(livePhysicalTemperatures[index], 1)} °C")
                if (pending != null) append(" · pending write")
            }
        }
        ntcEditors.forEachIndexed { index, fields ->
            val role = temperatureRoleEditors.getOrNull(index + 4)?.value
                ?: calibration.ntc[index].role
            val analogIndex = analogModeEditors.indexOfFirst {
                it.value == ControllerCalibration.ANALOG_MODE_NTC1 + index
            }
            fields.source.text = buildString {
                append("NTC${index + 1} · ")
                append(ControllerCalibration.temperatureRoleLabel(role))
                append(" · physical input: ")
                append(
                    ControllerCalibration.PHYSICAL_ANALOG_INPUTS.getOrNull(analogIndex)
                        ?: "not assigned",
                )
            }
        }

        afrLive.text = linearLiveText(
            liveAfr,
            "AFR",
            calibration,
            ControllerCalibration.ANALOG_MODE_AFR,
            2,
        )
        boostLive.text = linearLiveText(
            liveBoost,
            "bar",
            calibration,
            ControllerCalibration.ANALOG_MODE_BOOST,
            2,
        )
        oilPressureLive.text = linearLiveText(
            liveOilPressure,
            "bar",
            calibration,
            ControllerCalibration.ANALOG_MODE_OIL_PRESSURE,
            2,
        )

        val ntcIndex = calibration.oilTemperatureNtcIndex()
        val ntcMode = ntcIndex?.let { ControllerCalibration.ANALOG_MODE_NTC1 + it }
        oilTemperatureLive.text = if (ntcMode == null) {
            "LIVE · oil-temperature NTC not assigned"
        } else {
            linearLiveText(liveOilTemperature, "°C", calibration, ntcMode, 1)
        }
        vrefLive.text = if (liveVrefMillivolts > 0) {
            "LIVE  $liveVrefMillivolts mV · ${formatVolts(liveVrefMillivolts)} V"
        } else {
            "LIVE · waiting for reference data"
        }
    }

    private fun analogDecodedText(mode: Int): String? = when (mode) {
        ControllerCalibration.ANALOG_MODE_AFR -> "AFR ${formatLive(liveAfr, 2)}"
        ControllerCalibration.ANALOG_MODE_AFR2 -> "AFR2 ${formatLive(liveAfr2, 2)}"
        ControllerCalibration.ANALOG_MODE_BOOST -> "Boost ${formatLive(liveBoost, 2)} bar"
        ControllerCalibration.ANALOG_MODE_OIL_PRESSURE ->
            "Oil ${formatLive(liveOilPressure, 2)} bar"
        ControllerCalibration.ANALOG_MODE_FUEL_PRESSURE ->
            "Fuel ${formatLive(liveFuelPressure, 2)} bar"
        in ControllerCalibration.ANALOG_MODE_EGT1..ControllerCalibration.ANALOG_MODE_EGT4 -> {
            val index = mode - ControllerCalibration.ANALOG_MODE_EGT1
            "EGT${index + 1} ${liveEgt[index]} °C"
        }
        in ControllerCalibration.ANALOG_MODE_NTC1..ControllerCalibration.ANALOG_MODE_NTC3 -> {
            val index = mode - ControllerCalibration.ANALOG_MODE_NTC1
            "NTC${index + 1} ${formatLive(livePhysicalTemperatures[4 + index], 1)} °C"
        }
        ControllerCalibration.ANALOG_MODE_GEAR -> "Gear $liveGear"
        ControllerCalibration.ANALOG_MODE_EGT5,
        ControllerCalibration.ANALOG_MODE_EGT6 -> {
            val index = mode - ControllerCalibration.ANALOG_MODE_EGT5 + 4
            "EGT${index + 1} ${liveEgt[index]} °C"
        }
        ControllerCalibration.ANALOG_MODE_BATTERY ->
            "Battery ${formatLive(liveAdcVolts[0], 2)} V"
        else -> null
    }

    private fun linearLiveText(
        value: Float,
        unit: String,
        calibration: ControllerCalibration,
        analogMode: Int,
        decimals: Int,
    ): String = buildString {
        append("LIVE  ")
        append(formatLive(value, decimals))
        append(' ')
        append(unit)

        val input = calibration.inputForAnalogMode(analogMode)
        val channel = calibration.adcChannelForAnalogMode(analogMode)
        append(" · ")
        if (input == null || channel == null) {
            append("input not assigned")
            return@buildString
        }

        append(input)
        append(' ')
        append(formatLive(liveAdcVolts.getOrElse(channel) { Float.NaN }, 3))
        append(" V")
    }

    private fun formatLive(value: Float, decimals: Int): String =
        if (value.isFinite()) {
            String.format(Locale.US, "%.${decimals}f", value)
        } else {
            "—"
        }

    private fun clearLiveValues() {
        liveAfr = Float.NaN
        liveBoost = Float.NaN
        liveOilPressure = Float.NaN
        liveOilTemperature = Float.NaN
        liveAfr2 = Float.NaN
        liveFuelPressure = Float.NaN
        liveGear = 0
        liveVrefMillivolts = 0
        liveAdcVolts.fill(Float.NaN)
        livePhysicalTemperatures.fill(Float.NaN)
        liveEgt.fill(0)
        updateLiveReadouts()
    }

    private fun canShowLiveValues(): Boolean =
        visible && operation == Operation.IDLE && loadedCalibration != null

    private fun startLivePolling() {
        stopLivePolling()
        if (visible) mainHandler.post(livePoll)
    }

    private fun stopLivePolling() {
        mainHandler.removeCallbacks(livePoll)
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(2f), BORDER_COLOR)
        }

    private fun cancelTimeouts() {
        mainHandler.removeCallbacks(readTimeout)
        mainHandler.removeCallbacks(beginVerification)
        mainHandler.removeCallbacks(verifyTimeout)
    }

    private fun dp(value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
