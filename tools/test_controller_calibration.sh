#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

kotlinc \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/util/Hex.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/util/Numbers.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/protocol/TransmitterConstants.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/protocol/TransmitterPacket.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/protocol/TransmitterPacketParser.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/protocol/UsbPacket.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/transport/UsbRecoveryPolicy.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/utcomp/Binary.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/utcomp/ControllerCalibration.kt" \
  "$repo_root/tools/tests/ControllerCalibrationTest.kt" \
  -include-runtime \
  -d "$build_dir/controller-calibration-tests.jar"

java -jar "$build_dir/controller-calibration-tests.jar"
