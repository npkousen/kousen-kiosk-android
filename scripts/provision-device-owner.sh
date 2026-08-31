#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

ADB_DEVICE_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

./gradlew assembleDebug
adb devices
adb "${ADB_DEVICE_ARGS[@]}" install -t -r app/build/outputs/apk/debug/app-debug.apk

echo "Current owners:"
adb "${ADB_DEVICE_ARGS[@]}" shell dpm list-owners

echo "Setting Device Owner:"
adb "${ADB_DEVICE_ARGS[@]}" shell dpm set-device-owner cc.kousen.kiosk/.KioskDeviceAdminReceiver

echo "Launching kiosk:"
adb "${ADB_DEVICE_ARGS[@]}" shell am start -n cc.kousen.kiosk/.MainActivity

echo "Device Owner:"
adb "${ADB_DEVICE_ARGS[@]}" shell dpm list-owners

echo "Lock Task state:"
adb "${ADB_DEVICE_ARGS[@]}" shell dumpsys activity | grep -i lock || true

echo "Home activity:"
adb "${ADB_DEVICE_ARGS[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME || true
