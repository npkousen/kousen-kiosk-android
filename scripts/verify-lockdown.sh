#!/usr/bin/env bash
set -euo pipefail

adb shell dpm list-owners
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME || true
adb shell dumpsys activity | grep -i lock || true
adb shell dumpsys device_policy | grep -E -i "Device Owner|cc.kousen.kiosk|lockTask|lock task|testOnlyAdmin|DISALLOW|system update" || true
adb shell pm list packages -d || true
