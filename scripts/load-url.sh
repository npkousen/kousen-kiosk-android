#!/usr/bin/env bash
set -euo pipefail

URL="${1:-}"
ADB_DEVICE_ARGS=()

if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

if [[ -z "$URL" ]]; then
  echo "Usage: scripts/load-url.sh https://example.com/path" >&2
  echo "Only URLs whose origin is in the kiosk allowlist will load." >&2
  echo "Example: ADB_SERIAL=9878000E3FA8234 scripts/load-url.sh http://192.168.10.10:8000/" >&2
  exit 1
fi

adb "${ADB_DEVICE_ARGS[@]}" shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.LOAD_URL \
  --es url "$URL"
