# UTCOMP Dashboard

UTCOMP Dashboard is an Android 13+ dashboard and data logger for the UTCOMP
USB device. It provides configurable simple and Ralliart-style pages, alarm
thresholds, simulation, min/max tracking, high-resolution CSV logging, and an
interactive CSV graph viewer and a full-screen live signal inspector.

## Requirements

- Android 13 or newer
- USB host/OTG support
- UTCOMP USB device (`VID 1003`, `PID 52131`)
- JDK 17 and Android SDK 35 for local builds

Build the debug APK with:

```bash
./gradlew assembleDebug
```

## 1.0 architecture notes

The live USB path avoids packet-list boxing and unnecessary debug formatting.
USB polling runs on a dedicated looper instead of the UI thread. Transient
failures while writing idempotent data requests are retried before the session
is reopened, and recovery logs include the failed PID and last receive age. USB
connection state and decoded snapshots are delivered through callbacks instead
of being inferred from log text. Both dashboard modes retain their view
hierarchies and update only live values, colors, min/max labels, and overlays.
The CSV writer streams rows through a reusable builder path instead of creating
large temporary field lists. CSV viewing runs in a full-screen dark activity and
streams wide files with bounded graph memory, visible row progress, and no
per-line graph object allocation. AFR, boost, oil pressure, and oil temperature
remain the default graph rows, while additional logged channels can be selected
and loaded on demand. Extra rows scroll vertically, and a horizontal drag opens
a movable time window without losing the initial full-log overview. The top-bar
LOG button starts or stops capture
using the destination selected in the Data Log menu. Dashboard configuration
JSON is handled by a focused codec rather than the UI controller. The simple
dashboard uses a retained weighted grid with configurable pages from 1×1 through
4×4. Boxes can span adjacent cells, so values can be merged into wider or taller
cards without adding hard-coded layouts.

Edit mode provides page creation, duplication, reordering, grid presets, box
merge/split, editable border colors, and independent min/max sizing. Ralliart
rendering keeps its fixed instrument layout but uses the same per-sensor style
configuration, including split lower-corner oil min/max values. Its top status
bar has independent text scaling and visibility controls for outside/inside
temperature, battery voltage, and time. The fixed 1024×600 Ralliart design is
rendered in a density-independent design space and then uniformly fitted to the
available dashboard viewport, while the Android chrome continues to use dp/sp.
This keeps the radio layout unchanged and scales correctly on higher-resolution
phone displays. Simple cards can display the physical UTCOMP input or channel
name as a page-specific subtitle. Firmware and input mappings are refreshed
automatically after USB connects.

The top-bar CAL action opens a dedicated UTCOMP PRO sensor-calibration page.
It reads the current controller payloads before exposing AFR, boost, oil
pressure, and the active oil-temperature NTC profile. Physical ADC assignments,
calculated sensor values, raw input voltages, and the factory-calibrated ADC
reference are shown read-only. Edits are applied to copies of the exact 48-byte
controller packets, preserving every unrelated byte. Low-rate live polling runs
while the page is idle and pauses for settings operations. Changed packets and
the settings commit are each sent once without automatic write retries, then
the changed bytes are read back and verified. The last verified write can be
explicitly restored while the page remains open.

The normal controls panel contains only driver-facing dashboard actions. Manual
protocol requests and the automatic polling switch are grouped in a descriptive
Diagnostics menu, while simulation remains available from the top bar. Full
packet/decode tracing is disabled by default; Diagnostics shows one recent event
until full protocol logging is explicitly enabled. CSV log
actions and remaining in-app dialogs use the same dark, large-touch-target
design. App diagnostics are
recorded to a bounded private log with one rotated history file. The Diagnostics
menu can view, export, or clear lifecycle, USB recovery, CSV, UI-stall,
slow-render, memory-pressure, and uncaught-exception events without requiring an
app-focused logcat capture.

The Live Data action graphs one decoded value at its real packet update cadence.
Raw and filtered traces are shown together with rolling min/max, peak-to-peak
noise, standard deviation, average, and sample rate. Experimental live-fit notch
and counter-wave modes remain available for diagnostics, but the reusable
periodic correction is learned only during an explicit 35-second engine-off
calibration. That capture learns one common interference frequency from ADC 0
and separate amplitude/phase relationships for every signal with sufficient
sample rate and confidence. During normal use only small reference-frequency drift, phase, and amplitude
are tracked; engine-running values never change the saved per-signal profile.

Dashboard boxes can enable the saved correction independently per page/style.
The processing order is calibrated periodic cancellation followed by time-based
EMA smoothing. Smoothing offers Off, Light, Medium, Strong, and Custom time
constants, so channels with different packet rates have comparable response
times. Displayed min/max values use the conditioned signal, while alarms retain
the raw sensor value. CSV capture remains canonical raw data for later offline
analysis. Signal, diagnostic filter, calibration, smoothing, counter-wave gain,
and time-window choices are persisted, while Live Data sample collection runs
only while the full-screen inspector is open.

The Kotlin namespace is `de.krazey.utcomp.dashboard`. The Android
`applicationId` intentionally remains `de.krazey.utcomp.probe` so existing
installations can be upgraded without losing saved dashboard settings.
