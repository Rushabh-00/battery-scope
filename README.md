# BatteryScope

Android battery monitor focused on transparent battery measurements and estimates.

## Features

- Live battery level, charging state, temperature, voltage, current and power
- Device battery technology and charge-time information when Android exposes it
- Local charge-session tracking and full-charge detection
- Capacity estimation from repeated charging sessions
- Battery-health estimate with a confidence score
- Cycle information when the device exposes it
- Local temperature, voltage and current history
- Persistent background monitoring notification
- Clear separation between Android-reported values, calculated metrics and estimates

## Accuracy model

BatteryScope does not treat a single capacity number as ground truth. Capacity and health are estimated from repeated charging sessions and are intentionally shown with a confidence score. OEMs can expose different battery telemetry, so some values may be unavailable or approximate.

## Build

Open the project in Android Studio with a current Android Gradle Plugin/Kotlin-compatible environment and sync the Gradle project.

## GitHub Actions

The `Android` workflow builds debug and release APKs on pushes, pull requests and manual runs. It uploads both APKs as workflow artifacts.

To create a test release, open **Actions → Android → Run workflow** and leave **Publish the build as a GitHub test release** enabled. The workflow publishes the release APK as the prerelease `v0.2.0-test`.

Tagged versions (`v*`) automatically create normal GitHub releases with the release APK attached.
