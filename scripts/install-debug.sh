#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ADB_DEVICE_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

adb devices
adb "${ADB_DEVICE_ARGS[@]}" install -t -r app/build/outputs/apk/debug/app-debug.apk
adb "${ADB_DEVICE_ARGS[@]}" shell am start -n cc.kousen.kiosk/.MainActivity
