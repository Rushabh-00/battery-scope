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

The workflow runs JVM tests, builds one release APK, signs it with the repository signing secrets, verifies the signature, and publishes the single APK to a GitHub release.

## Current UI

The app uses Jetpack Compose and Material 3 with stable APIs only. The interface supports Auto, Light, Dark, and OLED presentation modes.

## Battery data

Current telemetry includes power, current, voltage, temperature, energy, charge level, charging state, remaining charge, learned capacity, health, wear, and charge/discharge session data.

Capacity learning requires a real low-to-full charging cycle. Session measurements are persisted across process restarts, protected against implausible fuel-gauge jumps, and require stable full-charge confirmation before completion. Each completed session also receives a quality score based on charge span, duration, measured amount, and agreement between remaining-charge deltas and current integration.

Battery health uses a robust quality-weighted median over recent completed sessions and exposes a confidence score that grows as independent measurements accumulate. Recent capacity measurements also feed a trend model so long-term capacity movement can be identified without making a single noisy session decisive.

Charge and discharge flow/time counters represent the current charging or discharging phase and reset when the direction changes. Live telemetry remains foreground-oriented and does not require a background service or notification system.
