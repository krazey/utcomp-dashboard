package de.krazey.utcomp.dashboard.logging

import android.content.Context
import android.os.SystemClock
import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

sealed class PeriodicCalibrationStatus {
    data object Missing : PeriodicCalibrationStatus()

    data class Ready(
        val profile: PeriodicNoiseCalibrationProfile,
    ) : PeriodicCalibrationStatus()

    data class Learning(
        val elapsedMs: Long,
        val durationMs: Long,
        val canFinish: Boolean,
        val referenceSamples: Int,
    ) : PeriodicCalibrationStatus() {
        val progress: Float
            get() = (elapsedMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }

    data class Failed(
        val reason: String,
    ) : PeriodicCalibrationStatus()
}

/**
 * Persists one vehicle-wide engine-off periodic-noise calibration and supplies
 * live phase-aligned correction components without relearning while driving.
 */
class PeriodicNoiseCalibrationManager(
    context: Context,
) {
    private companion object {
        const val PREFS_NAME = "utcomp_periodic_noise_calibration"
        const val PREF_PROFILE = "profileJson"
        const val PROFILE_VERSION = 1
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(PeriodicCalibrationStatus) -> Unit>()
    private val referenceTracker = PeriodicNoiseReferenceTracker()
    private val stateLock = Any()

    @Volatile
    private var profile: PeriodicNoiseCalibrationProfile? = decodeProfile(
        prefs.getString(PREF_PROFILE, null),
    )

    @Volatile
    private var session: PeriodicNoiseCalibrationSession? = null

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastLearningNotifyAtMs: Long = Long.MIN_VALUE

    init {
        referenceTracker.setProfile(profile)
    }

    fun addListener(listener: (PeriodicCalibrationStatus) -> Unit) {
        listeners += listener
        listener(status())
    }

    fun removeListener(listener: (PeriodicCalibrationStatus) -> Unit) {
        listeners -= listener
    }

    fun profile(): PeriodicNoiseCalibrationProfile? = profile

    fun status(nowMs: Long = SystemClock.elapsedRealtime()): PeriodicCalibrationStatus {
        val activeSession = session
        if (activeSession != null) {
            return PeriodicCalibrationStatus.Learning(
                elapsedMs = activeSession.elapsedMs(nowMs),
                durationMs = activeSession.durationMs,
                canFinish = activeSession.canFinish(nowMs),
                referenceSamples = activeSession.sampleCount("adc0"),
            )
        }
        lastFailure?.let { return PeriodicCalibrationStatus.Failed(it) }
        return profile?.let(PeriodicCalibrationStatus::Ready)
            ?: PeriodicCalibrationStatus.Missing
    }

    fun isLearning(): Boolean = session != null

    fun needsFastReferencePolling(): Boolean = session != null || profile != null

    fun startLearning(
        nowMs: Long = SystemClock.elapsedRealtime(),
        durationMs: Long = PeriodicNoiseCalibrationSession.DEFAULT_DURATION_MS,
    ) {
        synchronized(stateLock) {
            session = PeriodicNoiseCalibrationSession(nowMs, durationMs)
            lastFailure = null
            lastLearningNotifyAtMs = Long.MIN_VALUE
        }
        AppDiagnostics.info(
            "CALIBRATION",
            "Engine-off periodic-noise learning started durationMs=$durationMs",
        )
        notifyListeners()
    }

    fun cancelLearning() {
        val cancelled = synchronized(stateLock) {
            val existed = session != null
            session = null
            existed
        }
        if (cancelled) {
            AppDiagnostics.info("CALIBRATION", "Engine-off periodic-noise learning cancelled")
            notifyListeners()
        }
    }

    fun finishLearning(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        wallTimeMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): PeriodicCalibrationBuildResult? {
        val activeSession = synchronized(stateLock) { session } ?: return null
        if (!force && !activeSession.canFinish(nowElapsedMs)) return null

        val result = activeSession.build(wallTimeMs)
        synchronized(stateLock) {
            session = null
            if (result.profile != null) {
                profile = result.profile
                lastFailure = null
                prefs.edit().putString(PREF_PROFILE, encodeProfile(result.profile)).apply()
                referenceTracker.setProfile(result.profile)
            } else {
                // A failed replacement attempt must not disable a previously
                // validated vehicle profile. The caller still receives the
                // failure result and logs it for the user.
                lastFailure = if (profile == null) result.reason else null
            }
        }
        if (result.profile != null) {
            AppDiagnostics.info(
                "CALIBRATION",
                "Engine-off calibration saved frequencyHz=${result.profile.frequencyHz} " +
                    "referenceAmplitude=${result.profile.referenceAmplitude} " +
                    "referenceConfidence=${result.profile.referenceConfidence} " +
                    "signals=${result.acceptedSignals} rejected=${result.rejectedSignals}",
            )
        } else {
            AppDiagnostics.warning(
                "CALIBRATION",
                "Engine-off calibration failed: ${result.reason}",
            )
        }
        notifyListeners()
        return result
    }

    fun clear() {
        synchronized(stateLock) {
            session = null
            profile = null
            lastFailure = null
            prefs.edit().remove(PREF_PROFILE).apply()
            referenceTracker.setProfile(null)
        }
        AppDiagnostics.info("CALIBRATION", "Periodic-noise calibration cleared")
        notifyListeners()
    }

    fun hasCalibration(signalId: String): Boolean =
        profile?.calibrationFor(signalId) != null

    fun correction(signalId: String, timeMs: Long): PeriodicCorrection =
        synchronized(stateLock) { referenceTracker.correction(signalId, timeMs) }

    fun referenceFit(): HarmonicFit? = synchronized(stateLock) {
        referenceTracker.currentReferenceFit()
    }

    fun offerSnapshot(
        snapshot: UtcompDataSnapshot,
        sourcePid: Int,
        timeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        var shouldFinish = false
        synchronized(stateLock) {
            if (sourcePid == TransmitterConstants.UtcompPid.GENERAL_DATA1) {
                referenceTracker.addReference(timeMs, snapshot.adcInValCh0)
            }

            session?.let { activeSession ->
                LiveSignalCatalog.all.asSequence()
                    .filter { it.sourcePid == sourcePid }
                    .forEach { definition ->
                        activeSession.add(definition.id, timeMs, definition.read(snapshot))
                    }
                shouldFinish = activeSession.isComplete(timeMs)
            }
        }
        if (shouldFinish) {
            finishLearning(nowElapsedMs = timeMs, force = true)
        } else if (session != null && (
                lastLearningNotifyAtMs == Long.MIN_VALUE ||
                    timeMs - lastLearningNotifyAtMs >= 250L
            )
        ) {
            lastLearningNotifyAtMs = timeMs
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        val current = status()
        listeners.forEach { listener -> runCatching { listener(current) } }
    }

    private fun encodeProfile(profile: PeriodicNoiseCalibrationProfile): String =
        JSONObject().apply {
            put("version", PROFILE_VERSION)
            put("createdAtWallTimeMs", profile.createdAtWallTimeMs)
            put("durationMs", profile.durationMs)
            put("frequencyHz", profile.frequencyHz.toDouble())
            put("referenceSignalId", profile.referenceSignalId)
            put("referenceAmplitude", profile.referenceAmplitude.toDouble())
            put("referenceConfidence", profile.referenceConfidence.toDouble())
            put(
                "signals",
                JSONArray().apply {
                    profile.signals.values.forEach { signal ->
                        put(
                            JSONObject().apply {
                                put("signalId", signal.signalId)
                                put("amplitude", signal.amplitude.toDouble())
                                put("phaseOffsetDegrees", signal.phaseOffsetDegrees.toDouble())
                                put("confidence", signal.confidence.toDouble())
                                put("sampleRateHz", signal.sampleRateHz.toDouble())
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun decodeProfile(raw: String?): PeriodicNoiseCalibrationProfile? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version", 0) != PROFILE_VERSION) return@runCatching null
            val signals = LinkedHashMap<String, CalibratedSignalNoise>()
            val array = json.optJSONArray("signals") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("signalId")
                if (id.isBlank()) continue
                signals[id] = CalibratedSignalNoise(
                    signalId = id,
                    amplitude = item.optDouble("amplitude", Double.NaN).toFloat(),
                    phaseOffsetDegrees = item.optDouble(
                        "phaseOffsetDegrees",
                        Double.NaN,
                    ).toFloat(),
                    confidence = item.optDouble("confidence", Double.NaN).toFloat(),
                    sampleRateHz = item.optDouble("sampleRateHz", Double.NaN).toFloat(),
                )
            }
            PeriodicNoiseCalibrationProfile(
                version = json.optInt("version", PROFILE_VERSION),
                createdAtWallTimeMs = json.optLong("createdAtWallTimeMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                frequencyHz = json.optDouble("frequencyHz", Double.NaN).toFloat(),
                referenceSignalId = json.optString("referenceSignalId", "adc0"),
                referenceAmplitude = json.optDouble(
                    "referenceAmplitude",
                    Double.NaN,
                ).toFloat(),
                referenceConfidence = json.optDouble(
                    "referenceConfidence",
                    Double.NaN,
                ).toFloat(),
                signals = signals,
            ).takeIf {
                it.frequencyHz.isFinite() && it.signals.isNotEmpty()
            }
        }.getOrNull()
    }
}
