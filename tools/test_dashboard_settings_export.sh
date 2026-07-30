#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

kotlinc \
  "$repo_root/app/src/main/java/de/krazey/utcomp/dashboard/settings/DashboardSettingsExport.kt" \
  "$repo_root/tools/tests/DashboardSettingsExportTest.kt" \
  -include-runtime \
  -d "$build_dir/dashboard-settings-export-tests.jar"

java -jar "$build_dir/dashboard-settings-export-tests.jar"
