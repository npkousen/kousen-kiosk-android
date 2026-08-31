#!/usr/bin/env bash
set -euo pipefail

PIN="${1:-}"

if [[ ! "$PIN" =~ ^[0-9]{4,8}$ ]]; then
  echo "Usage: scripts/set-admin-pin.sh 1234" >&2
  echo "PIN must be 4-8 digits." >&2
  exit 1
fi

adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_ADMIN_PIN \
  --es pin "$PIN"

