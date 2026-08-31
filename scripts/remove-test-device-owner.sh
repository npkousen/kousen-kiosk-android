#!/usr/bin/env bash
set -euo pipefail

ADB_DEVICE_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

adb "${ADB_DEVICE_ARGS[@]}" shell dpm remove-active-admin cc.kousen.kiosk/.KioskDeviceAdminReceiver
adb "${ADB_DEVICE_ARGS[@]}" uninstall cc.kousen.kiosk
