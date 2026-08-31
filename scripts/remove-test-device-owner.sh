#!/usr/bin/env bash
set -euo pipefail

adb shell dpm remove-active-admin cc.kousen.kiosk/.KioskDeviceAdminReceiver
adb uninstall cc.kousen.kiosk
