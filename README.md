# Kousen Kiosk Android

Reusable Android kiosk shell for Kousen web experiences.

The app is intentionally small: Android owns the device boundary, and the configured website owns the product experience. The default v0.1 profile loads `https://kousen.kids`, but the package is generic and can be reconfigured for other Kousen kiosk profiles such as `https://kousen.cc`.

## Current Target

- First device: onn 8" Kids Tablet, 64 GB, 2026 model
- Android: 16
- API level: 36
- Manufacturer/model/device: `onn` / `36018344` / `onn8Kids`
- Application ID: `cc.kousen.kiosk`
- App name: `Kousen Kiosk`
- Device admin component: `cc.kousen.kiosk/.KioskDeviceAdminReceiver`

## Architecture

- `MainActivity`: full-screen WebView host, back handling, system bar hiding, profile intent handling.
- `KioskConfig`: persisted local profile model with `profile`, `name`, `homeUrl`, `allowedOrigins`, and `allowOfflineCache`.
- `KioskConfigStore`: `SharedPreferences` persistence. No backend is required.
- `KioskWebViewClient`: fail-closed top-level navigation guard. Disallowed links are consumed and never sent to Android or another app.
- `KioskPolicyManager`: isolated Device Owner, Device Admin, policy, and Lock Task Mode logic.
- `KioskDeviceAdminReceiver`: DPC receiver declaration and lock-task callbacks.
- `BootReceiver`: Device Owner-gated relaunch after boot or package replacement.
- `AdminModeController`: hidden seven-tap hook for future parent/admin mode. In v0.1 it only shows a debug-only toast.
- `KioskTextToSpeechBridge`: narrow Android TextToSpeech bridge for web content that uses `speechSynthesis`.
- `KioskSpeechSynthesisShim`: Web Speech API compatibility shim injected into allowed kiosk pages.

## Build

This project uses Kotlin, the Android SDK, AndroidX Activity/Core/WebKit, and the Android Gradle Plugin. It targets and compiles with API 36.

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run checks:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintDebug check
```

Repeatable helper scripts:

```sh
scripts/build-debug.sh
scripts/install-debug.sh
scripts/provision-device-owner.sh
scripts/verify-lockdown.sh
scripts/remove-test-device-owner.sh
```

## Install And Launch

```sh
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cc.kousen.kiosk/.MainActivity
```

Default behavior loads:

```text
https://kousen.kids
```

## Configure A Profile

Configuration is local and survives app restart, process death, and reboot. For v0.1, use an explicit ADB activity intent.

Kids profile:

```sh
adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_CONFIG \
  --es profile kids \
  --es name "Kousen Kids" \
  --es homeUrl https://kousen.kids \
  --es allowedOrigins https://kousen.kids
```

Command Center profile example:

```sh
adb shell am start \
  -n cc.kousen.kiosk/.MainActivity \
  -a cc.kousen.kiosk.action.SET_CONFIG \
  --es profile command-center \
  --es name "Kousen Command Center" \
  --es homeUrl https://kousen.cc \
  --es allowedOrigins https://kousen.cc
```

Multiple allowed origins can be comma-separated:

```sh
--es allowedOrigins https://kousen.kids,https://www.kousen.kids
```

The app currently requires HTTPS profile URLs and HTTPS allowed origins.

## Navigation Boundary

Allowed in the default profile:

```text
https://kousen.kids/*
```

Blocked by default:

```text
http://*
https://google.com/*
https://youtube.com/*
intent://*
file://*
content://*
market://*
mailto:*
tel:*
custom schemes
```

Blocked top-level navigations stay in the WebView. The app does not launch Chrome, Play Store, a chooser, or external intents for disallowed URLs.

## Back Behavior

Android back is handled through AndroidX Activity back dispatch. If WebView has history, the app calls `goBack()`. Otherwise it reloads the configured home URL. Back does not finish the activity or return to the launcher.

## WebView Storage And Offline Design

The app enables the WebView capabilities needed by modern web apps:

- JavaScript
- DOM storage and `localStorage`
- cookies for the configured origin
- Service Worker support
- default WebView cache behavior for offline-capable PWAs
- media playback without an extra native browser control surface

The app does not implement a native offline catalog, native game downloads, or duplicate service-worker logic. Offline availability should be implemented by the configured web origin.

Unsafe file/content access is disabled for the WebView and Service Workers. The only JavaScript/native bridge in v0.1 is the narrow TextToSpeech bridge described below.

### Text To Speech

Pop Party currently uses browser text-to-speech behavior rather than normal audio files. Chrome on Android can provide this through browser speech plumbing, but Android WebView can behave differently. The kiosk injects a narrow `speechSynthesis` shim into allowed pages at document start with AndroidX WebKit and backs it with Android `TextToSpeech`.

The native interface exposes only:

- `speak(payloadJson)`
- `cancel()`
- `isReady()`

It does not expose filesystem, shell, settings, package management, arbitrary intents, or general native APIs.

## Device Owner Provisioning

Current state from the brief:

```text
adb shell dpm list-owners
Device policy owners:
    no owners
```

After normal WebView behavior is stable, factory reset the tablet if required by Android provisioning rules, install the APK, then set the app as Device Owner:

```sh
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner cc.kousen.kiosk/.KioskDeviceAdminReceiver
```

Validate ownership:

```sh
adb shell dpm list-owners
```

Expected component:

```text
cc.kousen.kiosk/.KioskDeviceAdminReceiver
```

If `set-device-owner` fails because accounts or an owner already exist, Android normally requires removing those blockers or factory-resetting before provisioning. The app does not try to provision itself automatically.

For debug builds, the app declares `android:testOnly="true"` so development Device Owner state can be removed with:

```sh
adb shell dpm remove-active-admin cc.kousen.kiosk/.KioskDeviceAdminReceiver
adb uninstall cc.kousen.kiosk
```

Use factory reset as the reliable fallback if Android refuses to remove the Device Owner state.

## Lock Task Mode

When the app is Device Owner, `KioskPolicyManager`:

- allowlists `cc.kousen.kiosk` for Lock Task Mode
- sets `LOCK_TASK_FEATURE_NONE`
- applies a small set of kiosk-relevant user restrictions:
  - `DISALLOW_ADD_USER`
  - `DISALLOW_MODIFY_ACCOUNTS`
  - `DISALLOW_INSTALL_UNKNOWN_SOURCES`
  - `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`
  - `DISALLOW_SAFE_BOOT`

The activity declares:

```xml
android:lockTaskMode="if_whitelisted"
```

On resume, the app starts Lock Task Mode only if the package is allowlisted. In normal development installs, Device Owner-only policy calls are skipped and the WebView remains testable.

The activity reapplies Device Owner policies on resume and on delivered launch intents. This matters during development because the app may already be running when `dpm set-device-owner` succeeds.

The app also reapplies immersive system-bar hiding on resume, window focus, and WebView touch. On the tested onn tablet with three-button navigation, Android Lock Task Mode removes Home and Overview but may still transiently show the Back triangle after a system-edge swipe. Back is handled by the kiosk and returns to the configured home URL instead of leaving the app.

Confirm lock-task state on device:

```sh
adb shell dumpsys activity | grep -i lock
```

## Boot Relaunch

`BootReceiver` listens for:

- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

It launches `MainActivity` only when the app is Device Owner. This keeps normal development installs easy to uninstall and test.

## Debug Versus Release

Debug builds:

- enable WebView debugging
- log blocked navigation
- show debug toasts for blocked navigation and the future admin gesture hook

Release builds:

- do not enable WebView debugging
- do not expose an exit button
- do not expose parent/admin controls in v0.1

## Current Limitations

- No parent PIN or parent settings UI yet.
- No native Wi-Fi/settings escape flow yet.
- No remote provisioning backend by design.
- No native offline content downloader by design.
- Device Owner and Lock Task Mode still need validation on the physical onn tablet after provisioning.
- Pop Party and other web experiences need to be validated on the live site inside this WebView.

## Android API Notes

The implementation follows the current Android guidance for:

- WebView and Service Worker storage settings
- DeviceAdminReceiver-based DPC registration
- DevicePolicyManager Lock Task allowlisting
- WindowInsetsController-based system bar hiding
- AndroidX Activity back dispatch for predictive-back-era Android
