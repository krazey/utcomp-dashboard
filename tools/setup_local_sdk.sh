#!/usr/bin/env bash
set -euo pipefail

candidates=()
[[ -n "${ANDROID_HOME:-}" ]] && candidates+=("$ANDROID_HOME")
[[ -n "${ANDROID_SDK_ROOT:-}" ]] && candidates+=("$ANDROID_SDK_ROOT")
candidates+=("$HOME/Android/Sdk" "/opt/android-sdk" "/usr/lib/android-sdk")

sdk=""
for c in "${candidates[@]}"; do
  if [[ -d "$c/platforms" ]]; then
    sdk="$c"
    break
  fi
done

if [[ -z "$sdk" ]]; then
  cat >&2 <<MSG
Could not auto-detect Android SDK.
Install/open Android Studio once, then create local.properties manually, e.g.:
  sdk.dir=$HOME/Android/Sdk
MSG
  exit 1
fi

printf 'sdk.dir=%s\n' "$sdk" > local.properties
printf 'Wrote local.properties with sdk.dir=%s\n' "$sdk"

if [[ ! -d "$sdk/platforms/android-35" ]]; then
  echo "NOTE: compileSdk is 35, but $sdk/platforms/android-35 is not installed."
  echo "Install Android SDK Platform 35 in Android Studio SDK Manager, or change compileSdk to an installed platform."
fi
