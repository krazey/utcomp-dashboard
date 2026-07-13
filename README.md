# UTCOMP Dashboard

UTCOMP Dashboard is an Android 13+ dashboard and data logger for the UTCOMP
USB device. It provides configurable simple and Ralliart-style pages, alarm
thresholds, simulation, min/max tracking, high-resolution CSV logging, and an
interactive CSV graph viewer.

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
large temporary field lists. CSV preview loading runs outside the activity and
extracts only the displayed columns from wide log rows. Dashboard configuration
JSON is handled by a focused codec rather than the UI controller. The simple
dashboard uses a retained weighted grid with configurable pages from 1×1 through
4×4. Boxes can span adjacent cells, so values can be merged into wider or taller
cards without adding hard-coded layouts.

Edit mode provides page creation, duplication, reordering, grid presets, box
merge/split, editable border colors, and independent min/max sizing. Ralliart
rendering keeps its fixed instrument layout but uses the same per-sensor style
configuration, including split lower-corner oil min/max values. Its top status
bar has independent text scaling and visibility controls for outside/inside
temperature, battery voltage, and time.

The Kotlin namespace is `de.krazey.utcomp.dashboard`. The Android
`applicationId` intentionally remains `de.krazey.utcomp.probe` so existing
installations can be upgraded without losing saved dashboard settings.
