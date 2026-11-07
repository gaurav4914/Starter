# NetPulse — Android network monitor

NetPulse is an offline-first Android app built with Kotlin and Jetpack Compose.

## Features
- Live device-wide download and upload speed in the app.
- Ongoing foreground notification with current speed and today's data use.
- Separate mobile and Wi-Fi data totals from Android NetworkStatsManager.
- Automatic local-day reporting cycle from **1:00 AM to 1:00 AM**.
- Manual counter reset; the next automatic 1:00 AM cycle still applies.
- Six previous completed daily cycles in a compact history panel.
- Configurable daily data-budget alert (set 0 MB to disable).
- Optional monitoring auto-start after device reboot.
- No login, server, API key, analytics SDK, or internet permission required.

## First run
1. Install the APK.
2. Open NetPulse and allow **Usage Access** for the app in Android Settings.
3. Return to NetPulse and start monitoring.
4. Allow notifications when prompted.

## Android data and battery notes
Android controls access to device network-history records. Some OEM builds or Android versions may return delayed or incomplete counters. Mobile/Wi-Fi totals are based on Android's device-level records; carrier billing may differ due to rounding, tethering, zero-rated traffic, and carrier accounting. Live speed is calculated from device-wide TrafficStats byte-counter changes and may include background/VPN traffic. It is not a carrier-certified speed test. Battery optimization on some phones may affect background monitoring. The 1:00 AM reset changes the reporting window and does not erase Android's underlying network records.

The app uses a user-started foreground service and persistent notification. It does not inspect per-app private traffic.

## Build
JDK 17, Android SDK platform 35, Gradle 8.10.2:
`gradle assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds an APK on every push to main and publishes both a workflow artifact and a downloadable GitHub Release asset.
