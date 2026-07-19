# GeoAlign Browser (MVP)

A sideloadable Android privacy browser that aligns browser-visible location signals with the
apparent exit of an **already-running, user-operated** VPN. It does **not** implement a VPN, does
**not** change Android's system GPS, and makes **no anonymity guarantee**. See
[`docs/ARCHITECTURE_PLAN.md`](docs/ARCHITECTURE_PLAN.md) for the full plan and honesty constraints.

## Status

Milestone 1 (proofs of concept). This tree currently contains:

- Project + Gradle scaffold (single-activity Compose, version catalog, minimal permissions).
- `LocalNetworkPolicy` — CIDR / hostname / alt-notation blocker (spec §16) with an exhaustive unit-test suite.
- `ReadinessReducer` — pure readiness state machine (spec §7) with unit tests.
- `env_bundle.js` — the document-start environment bundle (geolocation / timezone / locale virtualization, spec §11–13).
- `MainActivity` — a **POC harness** that injects the bundle into a hardened WebView and loads a bundled diagnostics page (`poc.html`) exercising POC 1–4.
- GitHub Actions CI that runs unit tests and builds the debug APK.

This is not the finished browser; it is the risk-reduction step the spec requires before broad implementation.

## Building

The Android SDK and AndroidX are fetched from Google's Maven, which CI runners can reach.

```bash
# Unit tests (no device needed)
./gradlew testDebugUnitTest

# Static analysis
./gradlew lintDebug

# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Install on a connected device / emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Sanitized logs
adb logcat | grep -i geoalign
```

### CI (recommended)

Push to `main` or any `feature/**` branch, or run the **Android CI** workflow manually. It:
1. runs unit tests, 2. lints, 3. builds the debug APK, and 4. uploads `app-debug.apk` as a
downloadable artifact. Download it from the workflow run's *Artifacts* section and sideload it on
your phone — no local Android Studio required.

## Key toolchain versions

- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01
- `androidx.webkit:webkit:1.12.1` (document-start injection, UA-CH override)
- compileSdk/targetSdk 35, **minSdk 26**

> Note: the plan discusses moving to webkit 1.16.0 / API 36 once a green CI build is established.
> This scaffold pins conservative, known-good versions first so CI goes green, then upgrades.

## Permissions

Only `INTERNET` and `ACCESS_NETWORK_STATE`. All location / wifi / bluetooth / camera / mic
permissions are explicitly removed in the manifest and asserted absent by test. The app must
function without any location permission.
