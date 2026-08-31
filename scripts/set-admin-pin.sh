#!/usr/bin/env bash
set -euo pipefail

PIN="${1:-}"
ADB_DEVICE_ARGS=()

if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

if [[ ! "$PIN" =~ ^[0-9]{4,8}$ ]]; then
  echo "Usage: scripts/set-admin-pin.sh 1234" >&2
  echo "PIN must be 4-8 digits." >&2
  exit 1
fi

adb "${ADB_DEVICE_ARGS[@]}" shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_ADMIN_PIN \
  --es pin "$PIN"
