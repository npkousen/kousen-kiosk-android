#!/usr/bin/env bash
set -euo pipefail

CLEAR_CACHE="${1:-true}"
CLEAR_WEB_STORAGE="${2:-false}"

adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.REFRESH \
  --ez clearCache "$CLEAR_CACHE" \
  --ez clearWebStorage "$CLEAR_WEB_STORAGE"

