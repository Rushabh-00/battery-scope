# BatteryScope

Minimal Android battery telemetry and health monitor inspired by the information density of Beam/AccuBattery, with its own UI and implementation.

## Included

- Live signed power, current, voltage, temperature, battery level, remaining charge and energy
- Current in A or mA; charge in Ah or mAh; temperature in °C or °F; energy in Wh
- Persistent live notification with a selectable value rendered in the status-bar icon
- Selectable notification entries: W / A / mAh / °C / V / Wh / %
- Charge-time estimate and optional screen-state information
- Automatic device calibration when Android charge-counter telemetry is reliable; there is no manual Workarounds page
- Persistent screen-time and charging-session tracking
- Health estimate from measured charge added over large charging sessions
- Rolling capacity history, trend, confidence and wear information
- Dark/OLED-first presentation with Auto/Light/Dark modes

## Accuracy

BatteryScope uses Android `BatteryManager` properties for live telemetry. Current uses `CURRENT_NOW` with `CURRENT_AVERAGE` as fallback, then applies a small persistent automatic calibration only when the battery charge counter provides enough independent data to establish a stable scale. No OEM-specific multiplier is exposed to the user.

Health is an estimate: charge added is integrated from measured current, divided by the percentage gained, then extrapolated to a full battery. Only sessions covering at least 60 percentage points are considered usable for the health estimate, and the UI reports confidence instead of pretending the value is factory truth.

## Size / runtime

The release build enables R8 code shrinking and resource shrinking, keeps the dependency set deliberately small, filters packaged locales to English, and avoids custom font/image assets. Background polling slows down while the screen is off and the battery is discharging to reduce wakeups.

## GitHub Actions releases

The `Android` workflow is release-only. It keeps the five-job flow:

1. Prepare
2. Build Release
3. Quality checks
4. Package
5. Publish Release

Pushes to `main` create a signed prerelease test build. Tags matching `v*` create a normal signed GitHub release.

Add these repository secrets before the first signed release:

- `ANDROID_KEYSTORE_BASE64` — base64 encoded upload/release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow signs the release APK with the supplied keystore, verifies the signature, computes SHA-256, checks the final APK size, and publishes the APK plus checksum.
