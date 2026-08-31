#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${KOUSEN_KIOSK_KEYSTORE:-}" &&
      -n "${KOUSEN_KIOSK_KEYSTORE_PASSWORD:-}" &&
      -n "${KOUSEN_KIOSK_KEY_ALIAS:-}" &&
      -n "${KOUSEN_KIOSK_KEY_PASSWORD:-}" ]]; then
  echo "Building signed release APK..."
else
  echo "Release signing environment is incomplete; building unsigned release APK for validation only." >&2
  echo "Set KOUSEN_KIOSK_KEYSTORE, KOUSEN_KIOSK_KEYSTORE_PASSWORD, KOUSEN_KIOSK_KEY_ALIAS, and KOUSEN_KIOSK_KEY_PASSWORD for a signed APK." >&2
fi

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}" ./gradlew assembleRelease
