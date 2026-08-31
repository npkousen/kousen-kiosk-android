#!/usr/bin/env bash
set -euo pipefail

adb shell dpm list-owners
adb shell dumpsys activity | grep -i lock || true
adb shell dumpsys device_policy | grep -E -i "Device Owner|cc.kousen.kiosk|lockTask|lock task|testOnlyAdmin|DISALLOW" || true
