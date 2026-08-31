#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

adb devices
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cc.kousen.kiosk/.MainActivity
