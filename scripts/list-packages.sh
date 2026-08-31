#!/usr/bin/env bash
set -euo pipefail

echo "Third-party packages:"
adb shell pm list packages -3

echo
echo "System packages:"
adb shell pm list packages -s

echo
echo "Disabled packages:"
adb shell pm list packages -d

