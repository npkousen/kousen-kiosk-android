# Kousen Kiosk Android Handoff

## Current State

Kousen Kiosk Android is a generic WebView-based Android kiosk shell for Kousen web experiences. It is currently installed and tested on:

- onn 8" Kids Tablet for `https://kousen.kids`
- TCL 12" tablet for `https://kousen.cc` with local KousenTV and Plex origins

The app is provisioned as Device Owner for production-style lockdown. It uses Lock Task Mode, persistent Home activity registration, boot relaunch, status/navigation bar hiding, configurable URL allowlists, and native Admin Mode.

## Current Understanding

- The Android app should own device boundary, lockdown, lifecycle, admin escape, local settings handoff, and WebView hosting.
- The loaded websites should own the actual product experience: Kids, Command Center, TV, Games, and future Kousen profiles.
- Navigation is origin-allowlisted and fail-closed. HTTPS origins may be explicitly allowed. HTTP origins are accepted only for private/local network hosts.
- The KousenTV audio issue did not require an Android app change. KousenTV had persisted `localStorage.kousentv.masterVolume` as `0`; resetting it to `1` restored normal WebView playback state.
- The TCL status tint issue was addressed by stronger immersive/fullscreen re-hide behavior.
- Gesture-navigation tablets now have optional kiosk home gestures: left-edge swipe right and bottom-edge swipe up.

## Implemented App Features

- Device Owner and Lock Task Mode support
- persistent kiosk Home activity
- boot/package-replacement relaunch
- full-screen WebView with JavaScript, DOM storage, cookies, media playback, and Service Worker support
- origin allowlist for HTTPS and private/local HTTP services
- native TextToSpeech bridge for web content using `speechSynthesis`
- PIN-gated Admin Mode
- Admin Mode entry by touch gesture, remote/D-pad sequence, or ADB intent
- configurable homepage/profile
- configurable allowed HTTPS origins
- configurable allowed private/local HTTP origins
- configurable left-edge and bottom-edge home gestures
- brightness adjustment
- Wi-Fi settings handoff
- Bluetooth settings handoff
- reload, cache clear, site storage clear
- targeted web media reset for KousenTV-style persisted volume issues
- helper scripts for build, install, provision, verify, URL load, refresh, display, PIN, homepage, and release build

## Near-Term Roadmap

- Test signed release APK installation on both tablets.
- Add a known-good git tag after the two-tablet baseline is accepted.
- Add export/import for kiosk config JSON.
- Add a custom WebView error page for offline, DNS, TLS, HTTP, and renderer failures.
- Add optional auto-reload policies for network reconnect, screen wake, and selected WebView errors.
- Define profile defaults for Kids, Command Center, TV, and Games.

## Medium-Term Roadmap

- Local LAN remote admin, disabled by default.
- Local status endpoint for battery, Wi-Fi, app version, profile, current URL, uptime, and lock-task state.
- Trusted website-to-app bridge for Kousen origins, limited to commands such as go home, reload, media reset, brightness, and diagnostics.
- Screensaver or dim mode for dashboard/media tablets.
- More deliberate APK update flow for multiple devices.

## Probably Later Or Maybe Never

- Motion detection, camera, microphone, NFC, QR scanning, MQTT, cloud fleet management, and a full app launcher.
- These overlap with broader products like Fully Kiosk Browser, but they add permission, privacy, maintenance, and support complexity that is not needed for the current Kousen appliance model.

## Useful Commands

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug lintDebug
scripts/build-debug.sh
scripts/install-debug.sh
scripts/provision-device-owner.sh
scripts/verify-lockdown.sh
scripts/open-admin.sh
scripts/set-homepage.sh kids "Kousen Kids" https://kousen.kids https://kousen.kids true false
scripts/set-homepage.sh command-center "Kousen Command Center" https://kousen.cc https://kousen.cc,http://192.168.10.10:8000,http://192.168.10.10:32400 true true
scripts/build-release.sh
```
