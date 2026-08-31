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
- `AdminTriggerController`: hidden touch and remote-control trigger detection for Admin Mode.
- `AdminPinStore`: local 4-8 digit Admin Mode PIN storage. The development default is `2468`.
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
scripts/open-admin.sh
scripts/set-admin-pin.sh 1234
scripts/set-homepage.sh kids "Kousen Kids" https://kousen.kids
scripts/load-url.sh https://kousen.kids
scripts/refresh-web.sh
scripts/set-display.sh 180 1800000
scripts/remove-test-device-owner.sh
```

If more than one Android device may be connected, set `ADB_SERIAL` for the command:

```sh
ADB_SERIAL=9878000E3FA8234 scripts/verify-lockdown.sh
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

Configuration is local and survives app restart, process death, and reboot. Change it in Admin Mode or with an explicit ADB activity intent.

Kids profile:

```sh
scripts/set-homepage.sh kids "Kousen Kids" https://kousen.kids
```

Command Center profile example:

```sh
scripts/set-homepage.sh command-center "Kousen Command Center" https://kousen.cc
```

Multiple allowed origins can be comma-separated:

```sh
scripts/set-homepage.sh command-center "Kousen Command Center" https://kousen.cc https://kousen.cc,http://192.168.1.50:32400
```

Kousen Command Center with KousenTV and Plex on the current local network:

```sh
ADB_SERIAL=9878000E3FA8234 scripts/set-homepage.sh command-center "Kousen Command Center" https://kousen.cc https://kousen.cc,http://192.168.10.10:8000,http://192.168.10.10:32400
```

The homepage must be HTTPS. Allowed origins can be HTTPS origins or private/local HTTP origins such as `http://192.168.1.50:32400`, `http://10.0.0.20:3000`, `http://localhost:8080`, or `http://kousentv.local:3000`. Public internet HTTP origins are rejected.

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

This is an origin allowlist, not a page allowlist. To allow a local Plex server or KousenTV box, add its exact origin, including scheme and port. Example: use `http://192.168.1.50:32400` for Plex, not only `http://192.168.1.50`.

## WebView Troubleshooting

Debug builds log WebView page starts, finishes, HTTP errors, network errors, renderer exits, blocked navigations, and JavaScript console messages.

Useful focused logcat filter:

```sh
adb logcat KousenKiosk:D KioskWebViewClient:D KioskWebChrome:D chromium:E cr_media:E '*:S'
```

Load an allowlisted URL directly without changing the saved homepage:

```sh
ADB_SERIAL=9878000E3FA8234 scripts/load-url.sh http://192.168.10.10:8000/
ADB_SERIAL=9878000E3FA8234 scripts/load-url.sh 'http://192.168.10.10:32400/web/index.html#!/'
```

The direct-load command still enforces the kiosk allowlist. If it works but a card on the homepage does not, the issue is likely in the web page link or client-side routing. If both direct-load and homepage navigation fail, check the allowlist origin, tablet Wi-Fi, and logcat errors.

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

## Web Content Updates

The Android app is intended to become a stable WebView shell. Most day-to-day product changes should happen on `https://kousen.kids` without rebuilding the APK.

Normal reload paths:

- navigating back to the configured home URL
- using a refresh control inside `kousen.kids`, if the site adds one
- sending the kiosk refresh intent over USB:

```sh
scripts/refresh-web.sh
```

By default, `scripts/refresh-web.sh` clears the WebView HTTP cache and reloads the configured home URL. It does not clear WebView storage, so site state in local storage and IndexedDB should survive. If a service-worker or storage issue requires a stronger reset during development:

```sh
scripts/refresh-web.sh true true
```

Use the storage-clearing form carefully because it can remove saved site state.

The website should still use good cache/versioning behavior. A page-level refresh button is helpful, but it cannot always defeat stale service-worker or asset-cache behavior by itself.

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
- sets `MainActivity` as the persistent preferred Home activity
- sets `LOCK_TASK_FEATURE_NONE`
- applies kiosk-relevant user restrictions for users, accounts, unsafe installs, system settings, app-control panels, safe boot, factory reset, mounted media, overlays, and system error dialogs
- hides known consumer/store apps when those packages are installed, including Play Store, YouTube, Gmail, Photos, Maps, Messages, Search, and Feedback
- sets Android system updates to install only during a 3:00-4:00 AM local maintenance window

The activity declares:

```xml
android:lockTaskMode="if_whitelisted"
```

On resume, the app starts Lock Task Mode only if the package is allowlisted. In normal development installs, Device Owner-only policy calls are skipped and the WebView remains testable.

The activity reapplies Device Owner policies on resume and on delivered launch intents. This matters during development because the app may already be running when `dpm set-device-owner` succeeds.

After the full Device Owner policy pass succeeds once in the current app process, later resumes skip the expensive parts such as package inventory and app hiding. Lock Task re-entry is still checked on every resume.

The app also reapplies immersive system-bar hiding on resume, window focus, and WebView touch. On the tested onn tablet with three-button navigation, Android Lock Task Mode removes Home and Overview but may still transiently show the Back triangle after a system-edge swipe. Back is handled by the kiosk and returns to the configured home URL instead of leaving the app.

As Device Owner, the app also requests:

- keyguard disabled, so power/wake should return directly to the kiosk when no PIN/password is set
- status bar disabled outside Lock Task Mode where Android honors it
- 30-minute screen-off timeout
- stay awake while plugged into AC, USB, or wireless charging

Volume adjustment is intentionally not restricted because the kiosk uses music, sound effects, and TTS.

Confirm lock-task state on device:

```sh
adb shell dumpsys activity | grep -i lock
```

## Android App And OS Updates

APK updates are separate from web content updates.

During development:

```sh
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cc.kousen.kiosk/.MainActivity
```

Because the app is the persistent Home activity and listens for package replacement, replacing the APK should return to the kiosk without needing a tablet reboot.

For production, prefer signed release APKs and a deliberate update path: USB/ADB for local devices, an MDM/Android Management API deployment for a fleet, or a future in-app Device Owner installer using Android's package installer APIs.

Android OS updates cannot be disabled forever with standard Device Owner APIs. The current policy limits installation to a 3:00-4:00 AM local maintenance window. Android also supports temporary postponement and freeze periods, but those are bounded and should be used only for known critical periods.

Some OEM firmware updaters can still show a pending-restart dialog after an update has already been installed. On the TCL 9445X, this came from `com.tcl.fota.system` with the message `Restart required`. The maintenance window controls when updates are allowed to install, but it does not guarantee that an already-staged firmware update can be made invisible forever.

For dedicated devices, Kousen Kiosk hides known consumer, setup, help, demo, and OEM updater surfaces when they are installed. This is done through Device Owner `setApplicationHidden`, which is reversible policy, not package deletion. Android may still keep core privileged services alive when the vendor marks them as required.

## Boot Relaunch

`BootReceiver` listens for:

- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

It launches `MainActivity` only when the app is Device Owner. This keeps normal development installs easy to uninstall and test.

Test reboot behavior:

```sh
adb reboot
adb wait-for-device
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys activity | grep -i cc.kousen.kiosk
adb shell dumpsys activity | grep -i lock
```

## Admin Mode

Admin Mode is native and works across kiosk profiles. It does not depend on `kousen.kids`, KousenTV, or a command-center page being loaded.

Entry paths:

- touch tablets: hold the top-left corner for about 2 seconds until the haptic cue, release, then tap the top-right corner 2 times within 12 seconds
- TV remotes/keyboards: `Up Up Down Down Left Right Left Right Select Select` within 8 seconds
- ADB maintenance:

```sh
scripts/open-admin.sh
```

The development default PIN is:

```text
2468
```

Set a device-specific PIN before real use:

```sh
scripts/set-admin-pin.sh 1234
```

Admin Mode currently includes:

- homepage/profile editing with presets for Kousen Kids, Kousen Command Center, and Kousen Games
- allowed-origin editing for private/local HTTP targets such as Plex or a KousenTV box
- brightness adjustment
- page reload
- cache-clear reload
- site-storage-clear reload
- Wi-Fi settings handoff

When opening Wi-Fi settings, the kiosk temporarily relaxes Wi-Fi/app-control restrictions and stops Lock Task. It schedules a return to Kousen Kiosk after 2 minutes and reapplies full kiosk policies on return.

The homepage editor writes to the same local profile store as `ACTION_SET_CONFIG`, so the selected URL survives app restart, process death, and reboot.

## Display, Brightness, And Wi-Fi

The production child-facing kiosk should not expose brightness, Wi-Fi, or Android Settings directly. Those belong behind a future parent/admin mode.

For development, brightness and screen timeout can be adjusted over USB:

```sh
scripts/set-display.sh 180 1800000
```

The first argument is brightness from `1` to `255`. The second argument is screen-off timeout in milliseconds.

For Wi-Fi changes today, use one of these admin paths:

- make the change before entering final Lock Task testing
- use USB ADB while the tablet is physically in hand
- remove the debug Device Owner during development, change Settings, then reprovision

Admin Mode should remain the only in-person way to reach Wi-Fi or display controls in production.

## Package Inventory

Lock Task prevents users from opening non-allowlisted apps, and Device Owner policy hides a conservative list of known consumer apps when installed. Device-specific bloatware should be audited per tablet model instead of hidden blindly.

Use:

```sh
scripts/list-packages.sh
```

Do not hide or suspend WebView, Chrome if it is the WebView provider, Google Play services, Android System, package installer, Settings, keyboard, or TTS packages unless the device has been tested afterward.

## Debug Versus Release

Debug builds:

- enable WebView debugging
- log blocked navigation
- show debug toasts for blocked navigation and admin maintenance actions

Release builds:

- do not enable WebView debugging
- do not expose an exit button
- keep Admin Mode controls PIN-gated

## Current Limitations

- Admin Mode is intentionally small; there is no general-purpose Settings launcher yet.
- No remote provisioning backend by design.
- No native offline content downloader by design.
- Samsung and other vendor tablets need package-inventory review before hiding vendor apps.
- Initial boot can still feel slower while Android, WebView, network, and the website warm up on low-end tablets.

## Android API Notes

The implementation follows the current Android guidance for:

- WebView and Service Worker storage settings
- DeviceAdminReceiver-based DPC registration
- DevicePolicyManager Lock Task allowlisting
- DevicePolicyManager package hiding and system update policy
- WindowInsetsController-based system bar hiding
- AndroidX Activity back dispatch for predictive-back-era Android
