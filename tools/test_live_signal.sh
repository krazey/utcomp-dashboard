#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/utcomp-live-signal-test"
rm -rf "$OUT"
mkdir -p "$OUT"

kotlinc \
  "$ROOT/app/src/main/java/de/krazey/utcomp/dashboard/protocol/TransmitterConstants.kt" \
  "$ROOT/app/src/main/java/de/krazey/utcomp/dashboard/utcomp/UtcompDataSnapshot.kt" \
  "$ROOT/app/src/main/java/de/krazey/utcomp/dashboard/logging/LiveSignalInspector.kt" \
  "$ROOT/tools/tests/LiveSignalInspectorTest.kt" \
  -include-runtime \
  -d "$OUT/test.jar"

java -jar "$OUT/test.jar"
