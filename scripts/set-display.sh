#!/usr/bin/env bash
set -euo pipefail

BRIGHTNESS="${1:-180}"
TIMEOUT_MS="${2:-1800000}"

if [[ "$BRIGHTNESS" -lt 1 || "$BRIGHTNESS" -gt 255 ]]; then
  echo "Brightness must be 1-255." >&2
  exit 1
fi

adb shell settings put system screen_brightness_mode 0
adb shell settings put system screen_brightness "$BRIGHTNESS"
adb shell settings put system screen_off_timeout "$TIMEOUT_MS"

echo "Set brightness=$BRIGHTNESS and screen_off_timeout=${TIMEOUT_MS}ms"
