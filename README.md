# BatteryScope

Clean Android foundation for BatteryScope.

This repository is intentionally starting from a minimal, buildable base. Battery telemetry, capacity estimation, health analysis, monitoring, and UI will be added in small verified steps.

## Build

GitHub Actions builds the release APK on `v*` tags or by manual workflow dispatch.

## Release signing secrets

The release workflow expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
