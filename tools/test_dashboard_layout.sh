#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

kotlinc \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/utcomp/UtcompDataSnapshot.kt" \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/dashboard/DashboardConfig.kt" \
  "$repo_root/tools/tests/DashboardLayoutTest.kt" \
  -include-runtime \
  -d "$build_dir/dashboard-layout-tests.jar"

java -jar "$build_dir/dashboard-layout-tests.jar"
