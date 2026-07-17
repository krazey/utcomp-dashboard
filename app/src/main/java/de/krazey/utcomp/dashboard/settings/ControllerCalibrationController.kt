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
import java.util.Locale

/** Full-screen, USB-backed editor for the verified UTCOMP calibration fields. */
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

    private companion object {
        const val READ_TIMEOUT_MS = 4_000L
        const val WRITE_SETTLE_MS = 550L
        const val VERIFY_TIMEOUT_MS = 4_000L

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
    private lateinit var mappingText: TextView
    private lateinit var oilNtcTitle: TextView
    private lateinit var oilNtcSource: TextView
    private lateinit var refreshButton: Button
    private lateinit var writeButton: Button
    private lateinit var undoButton: Button

    private lateinit var afrA: EditText
    private lateinit var afrB: EditText
    private lateinit var boostA: EditText
    private lateinit var boostB: EditText
    private lateinit var oilPressureA: EditText
    private lateinit var oilPressureB: EditText
    private lateinit var ntcR25: EditText
    private lateinit var ntcBeta: EditText
    private lateinit var vrefMillivolts: EditText
    private val editors = mutableListOf<EditText>()

    private var operation = Operation.IDLE
    @Volatile
    private var visible = false
    private var populatingEditors = false
    private var loadedCalibration: ControllerCalibration? = null
    private var oilNtcIndex: Int? = null
    private var originalPayloads: Map<Int, ByteArray> = emptyMap()
    private val receivedPayloads = linkedMapOf<Int, ByteArray>()
    private val pendingVerification = linkedMapOf<Int, VerificationTarget>()
    private val verificationFailures = mutableListOf<String>()
    private var pendingRollbackCandidate: RollbackBytes? = null
    private var lastRollback: RollbackBytes? = null
    private var pendingWriteIsRollback = false
    private var previousRequestedOrientation: Int? = null

    private val readTimeout = Runnable {
        if (operation != Operation.READING) return@Runnable
        operation = Operation.IDLE
        loadedCalibration = null
        setStatus(
            "Read timed out (${receivedPayloads.size}/${ControllerCalibrationCodec.requiredPids.size} packets). " +
                "Check USB and tap Refresh.",
            ERROR_COLOR,
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
        pendingVerification.keys.forEach(requestPacket)
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

        mappingText = TextView(activity).apply {
            text = "Input assignments will appear after Refresh."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = roundedBackground(PANEL_COLOR, 14f)
        }
        content.addView(mappingText, panelLayoutParams())

        afrA = numberEditor("2")
        afrB = numberEditor("10")
        content.addView(
            linearSection(
                title = "AFR",
                description = "AFR = a × input voltage + b",
                firstLabel = "a",
                first = afrA,
                secondLabel = "b",
                second = afrB,
            ),
            panelLayoutParams(),
        )

        boostA = numberEditor("2")
        boostB = numberEditor("-1")
        content.addView(
            linearSection(
                title = "Boost pressure",
                description = "bar = a × input voltage + b",
                firstLabel = "a",
                first = boostA,
                secondLabel = "b",
                second = boostB,
            ),
            panelLayoutParams(),
        )

        oilPressureA = numberEditor("2.5")
        oilPressureB = numberEditor("-1.25")
        content.addView(
            linearSection(
                title = "Oil pressure",
                description = "bar = a × input voltage + b",
                firstLabel = "a",
                first = oilPressureA,
                secondLabel = "b",
                second = oilPressureB,
            ),
            panelLayoutParams(),
        )

        ntcR25 = numberEditor("10250")
        ntcBeta = numberEditor("3512")
        content.addView(ntcSection(), panelLayoutParams())

        vrefMillivolts = integerEditor("5212")
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
                operation = Operation.IDLE
                loadedCalibration = null
                originalPayloads = emptyMap()
                receivedPayloads.clear()
                pendingVerification.clear()
                lastRollback = null
                setStatus("UTCOMP disconnected. Reconnect it and tap Refresh.", ERROR_COLOR)
                updateEnabledState()
            }
        }
    }

    fun onPacketReceived(packet: TransmitterPacket) {
        if (!visible || !ControllerCalibrationCodec.accepts(packet.pid)) return
        if (packet.cmd != TransmitterConstants.Command.TRANSFER_DATA) return
        if (packet.source != TransmitterConstants.Source.DEVICE) return
        if (packet.data.size != 48) return

        val pid = packet.pid
        val payload = packet.data.copyOf()
        activity.runOnUiThread {
            if (visible) receivePayload(pid, payload)
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
        operation = Operation.READING
        loadedCalibration = null
        originalPayloads = emptyMap()
        receivedPayloads.clear()
        lastRollback = null
        pendingVerification.clear()
        verificationFailures.clear()
        setStatus(
            "Reading 0/${ControllerCalibrationCodec.requiredPids.size} settings packets…",
            WARNING_COLOR,
        )
        updateEnabledState()
        ControllerCalibrationCodec.requiredPids.forEach(requestPacket)
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
        receivedPayloads[pid] = payload
        val count = ControllerCalibrationCodec.requiredPids.count(receivedPayloads::containsKey)
        setStatus(
            "Reading $count/${ControllerCalibrationCodec.requiredPids.size} settings packets…",
            WARNING_COLOR,
        )
        if (count != ControllerCalibrationCodec.requiredPids.size) return

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
            "Loaded directly from UTCOMP · Vref ${formatVolts(decoded.vrefMillivolts)} V",
            GOOD_COLOR,
        )
        appendLog("Controller calibration read complete")
        updateEnabledState()
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
        if (pendingVerification.isNotEmpty()) return

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
        setStatus("$message. Refresh before another write.", ERROR_COLOR)
        appendLog("Controller calibration verification failed: $message")
        updateEnabledState()
    }

    private fun readEditors(base: ControllerCalibration): ControllerCalibration {
        val editedNtc = base.ntc.toMutableList()
        oilNtcIndex?.let { index ->
            val currentNtc = editedNtc[index]
            editedNtc[index] = currentNtc.copy(
                r25Ohms = parseFloat(ntcR25, "Oil temperature R25")
                    .requireRange(100f, 1_000_000f, "Oil temperature R25"),
                betaKelvin = parseFloat(ntcBeta, "Oil temperature beta")
                    .requireRange(100f, 10_000f, "Oil temperature beta"),
            )
        }

        val vref = vrefMillivolts.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("Vref must be an integer in millivolts")
        require(vref in 4_000..6_000) { "Vref must be between 4000 and 6000 mV" }

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
            vrefMillivolts = vref,
        )
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
            vrefMillivolts.setText(calibration.vrefMillivolts.toString())

            oilNtcIndex = calibration.oilTemperatureNtcIndex()
            val ntcIndex = oilNtcIndex
            if (ntcIndex != null) {
                val ntc = calibration.ntc[ntcIndex]
                ntcR25.setText(format(ntc.r25Ohms))
                ntcBeta.setText(format(ntc.betaKelvin))
                oilNtcTitle.text = "Oil temperature · NTC${ntcIndex + 1}"
                oilNtcSource.text =
                    "Physical input: ${calibration.physicalInputForNtc(ntcIndex) ?: "not assigned"} · " +
                        "T0/correction ${format(ntc.correctionCelsius)} °C is preserved unchanged"
            } else {
                ntcR25.setText("")
                ntcBeta.setText("")
                oilNtcTitle.text = "Oil temperature"
                oilNtcSource.text =
                    "No NTC profile is assigned to the oil-temperature role; NTC editing is disabled."
            }

            mappingText.text = buildString {
                append("Controller input assignments\n")
                append("AFR: ")
                append(calibration.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_AFR) ?: "not assigned")
                append("   ·   Boost: ")
                append(calibration.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_BOOST) ?: "not assigned")
                append("\nOil pressure: ")
                append(calibration.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_OIL_PRESSURE) ?: "not assigned")
                append("   ·   Oil temperature: ")
                append(
                    ntcIndex?.let { index ->
                        "NTC${index + 1} / ${calibration.physicalInputForNtc(index) ?: "not assigned"}"
                    } ?: "not assigned",
                )
            }
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
        oilNtcIndex?.let { index ->
            val old = before.ntc[index]
            val new = after.ntc[index]
            if (!sameFloat(old.r25Ohms, new.r25Ohms)) {
                add("NTC${index + 1} R25: ${format(old.r25Ohms)} → ${format(new.r25Ohms)} Ω")
            }
            if (!sameFloat(old.betaKelvin, new.betaKelvin)) {
                add("NTC${index + 1} beta: ${format(old.betaKelvin)} → ${format(new.betaKelvin)} K")
            }
        }
        if (before.vrefMillivolts != after.vrefMillivolts) {
            add("Vref: ${before.vrefMillivolts} → ${after.vrefMillivolts} mV")
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
                text = "Controller calibration"
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
            "Values are read from UTCOMP PRO, not app defaults. Live polling pauses while this " +
                "page is open. A write changes only verified calibration bytes, sends no " +
                "automatic retry, commits once, and must pass read-back verification."
        textSize = 12f
        setTextColor(SECONDARY_TEXT)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = roundedBackground(Color.rgb(8, 10, 14), 14f)
        layoutParams = panelLayoutParams()
    }

    private fun linearSection(
        title: String,
        description: String,
        firstLabel: String,
        first: EditText,
        secondLabel: String,
        second: EditText,
    ): View = section(title, description).apply {
        addView(editorRow(firstLabel, first, secondLabel, second))
    }

    private fun ntcSection(): View = section("", "").apply {
        oilNtcTitle = TextView(activity).apply {
            text = "Oil temperature"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(oilNtcTitle)
        oilNtcSource = TextView(activity).apply {
            text = "Active NTC profile will be detected from the controller."
            textSize = 11.5f
            setTextColor(SECONDARY_TEXT)
            setPadding(0, dp(2f), 0, dp(7f))
        }
        addView(oilNtcSource)
        addView(editorRow("R25 (Ω)", ntcR25, "Beta (K)", ntcBeta))
    }

    private fun vrefSection(): View = section(
        title = "ADC reference voltage",
        description = "Measured controller reference in millivolts; normally close to 5200 mV.",
    ).apply {
        addView(editorRow("Vref (mV)", vrefMillivolts, null, null))
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

    private fun integerEditor(hintText: String): EditText =
        createEditor(hintText, InputType.TYPE_CLASS_NUMBER)

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
        val ntcAvailable = canEdit && oilNtcIndex != null
        if (::ntcR25.isInitialized) ntcR25.isEnabled = ntcAvailable
        if (::ntcBeta.isInitialized) ntcBeta.isEnabled = ntcAvailable
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
