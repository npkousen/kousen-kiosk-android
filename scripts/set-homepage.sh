#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-}"
NAME="${2:-}"
HOME_URL="${3:-}"
ALLOWED_ORIGINS="${4:-$HOME_URL}"

if [[ -z "$PROFILE" || -z "$NAME" || -z "$HOME_URL" ]]; then
  echo "Usage: scripts/set-homepage.sh profile name https://example.com [https://example.com,https://www.example.com]" >&2
  exit 1
fi

adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_CONFIG \
  --es profile "$PROFILE" \
  --es name "$NAME" \
  --es homeUrl "$HOME_URL" \
  --es allowedOrigins "$ALLOWED_ORIGINS"

