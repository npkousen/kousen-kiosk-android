#!/usr/bin/env bash
set -euo pipefail

ADB_DEVICE_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

adb "${ADB_DEVICE_ARGS[@]}" shell dpm list-owners
adb "${ADB_DEVICE_ARGS[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME || true
adb "${ADB_DEVICE_ARGS[@]}" shell dumpsys activity | grep -i lock || true
adb "${ADB_DEVICE_ARGS[@]}" shell dumpsys device_policy | grep -E -i "Device Owner|cc.kousen.kiosk|lockTask|lock task|testOnlyAdmin|DISALLOW|system update" || true
adb "${ADB_DEVICE_ARGS[@]}" shell pm list packages -d || true
