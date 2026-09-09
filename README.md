# BatteryScope

A lightweight Android battery telemetry app built with a deliberately small, separated architecture.

## Project structure

```text
app/src/main/java/com/batteryscope/app/
├── battery/   Battery readings and measurement logic
├── settings/  Persistent app and UI preferences
└── ui/        Compose UI and theme
```

The UI and measurement code are kept separate so telemetry can be improved without redesigning the interface.

## Build

The repository has one GitHub Actions workflow. It runs on every push to `main` and can also be started manually.

The workflow builds one release APK, signs it with the repository signing secrets, verifies the signature, and publishes the single APK to a GitHub release.

## Release signing secrets

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Current UI

The app uses Jetpack Compose and Material 3 with stable APIs only. The interface supports Auto, Light, Dark, and OLED presentation modes.

## Battery data

Current telemetry includes power, current, voltage, temperature, energy, charge level, charging state, remaining charge, estimated capacity, and session data. Capacity and health estimation are designed to improve from real charging sessions rather than from a fixed battery-size assumption.
