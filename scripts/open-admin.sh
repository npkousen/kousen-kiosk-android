#!/usr/bin/env bash
set -euo pipefail

adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.ADMIN

