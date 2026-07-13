#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/utcomp-periodic-calibration-test"
rm -rf "$OUT"
mkdir -p "$OUT"

kotlinc \
  "$ROOT/app/src/main/java/de/krazey/utcomp/dashboard/logging/PeriodicNoiseCalibration.kt" \
  "$ROOT/app/src/main/java/de/krazey/utcomp/dashboard/dashboard/DashboardSignalConditioning.kt" \
  "$ROOT/tools/tests/PeriodicNoiseCalibrationTest.kt" \
  -include-runtime \
  -d "$OUT/test.jar"

java -jar "$OUT/test.jar"
