#!/usr/bin/env bash
set -euo pipefail

ADB_DEVICE_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ADB_SERIAL")
fi

echo "Third-party packages:"
adb "${ADB_DEVICE_ARGS[@]}" shell pm list packages -3

echo
echo "System packages:"
adb "${ADB_DEVICE_ARGS[@]}" shell pm list packages -s

echo
echo "Disabled packages:"
adb "${ADB_DEVICE_ARGS[@]}" shell pm list packages -d
