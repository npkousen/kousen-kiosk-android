#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

./gradlew assembleDebug
adb devices
adb install -t -r app/build/outputs/apk/debug/app-debug.apk

echo "Current owners:"
adb shell dpm list-owners

echo "Setting Device Owner:"
adb shell dpm set-device-owner cc.kousen.kiosk/.KioskDeviceAdminReceiver

echo "Launching kiosk:"
adb shell am start -n cc.kousen.kiosk/.MainActivity

echo "Device Owner:"
adb shell dpm list-owners

echo "Lock Task state:"
adb shell dumpsys activity | grep -i lock || true
