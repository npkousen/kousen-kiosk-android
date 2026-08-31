#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-}"
NAME="${2:-}"
HOME_URL="${3:-}"
ALLOWED_ORIGINS="${4:-$HOME_URL}"
ADB_DEVICE_ARGS=()

if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

if [[ -z "$PROFILE" || -z "$NAME" || -z "$HOME_URL" ]]; then
  echo "Usage: scripts/set-homepage.sh profile name https://example.com [allowed-origins]" >&2
  echo "Allowed origins can include HTTPS origins and private/local HTTP origins, comma-separated." >&2
  echo "Example: scripts/set-homepage.sh command-center \"Kousen Command Center\" https://kousen.cc https://kousen.cc,http://192.168.10.10:8000,http://192.168.10.10:32400" >&2
  exit 1
fi

adb "${ADB_DEVICE_ARGS[@]}" shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_CONFIG \
  --es profile "$PROFILE" \
  --es name "$NAME" \
  --es homeUrl "$HOME_URL" \
  --es allowedOrigins "$ALLOWED_ORIGINS"
