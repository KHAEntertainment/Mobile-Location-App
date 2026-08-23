# GeoAlign Browser (MVP)

A sideloadable Android privacy browser that aligns browser-visible location signals with the
apparent exit of an **already-running, user-operated** VPN. It does **not** implement a VPN, does
**not** change Android's system GPS, and makes **no anonymity guarantee**. See
[`docs/ARCHITECTURE_PLAN.md`](docs/ARCHITECTURE_PLAN.md) for the full plan and honesty constraints.

## Status

Milestones 1–3 are implemented and validated on-device. This tree contains:

- **M1 — foundations.** Gradle scaffold (single-activity Compose, version catalog, minimal
  permissions), `LocalNetworkPolicy` (CIDR / hostname / alt-notation blocker, spec §16) with an
  exhaustive unit-test suite, `ReadinessReducer` (pure readiness state machine, spec §7), and
  `env_bundle.js` — the document-start environment bundle (geolocation / timezone / locale
  virtualization, spec §11–13). A diagnostics page (`poc.html`) still exercises the POCs.
- **M2 — data foundation.** VPN / effective-IP / IP-geolocation repositories, `ReadinessService`,
  JSON profile storage with Android Keystore for API keys, the profile editor, and "Match Browser
  to VPN".
- **M3 — the browser.** Multi-tab browsing over a single hardened WebView, device emulation
  (`device_bundle.js` + UA / UA-CH), SSL and external-scheme policy, downloads, and clear-session.
- **M6 (in progress) — UX.** A design system (`ui/theme`) and shared components, and a readiness
  screen built around one question: is the browser aligned and safe to open? Screen state is derived
  by a pure, unit-tested presenter (`ui/state`) rather than inside composables, and
  `AlignmentChecker` (`core/alignment`) compares the saved profile against the live exit so a
  drifted or stale profile can no longer read as ready.
- GitHub Actions CI: unit tests → lint → debug APK.

**191 unit tests, 0 failures.** Build and iterate locally — see
[`docs/HANDOFF.md`](docs/HANDOFF.md) §6 for the toolchain, and
[`docs/TROUBLESHOOTING_WEBVIEW.md`](docs/TROUBLESHOOTING_WEBVIEW.md) for diagnosing the WebView.

## Building

The Android SDK and AndroidX are fetched from Google's Maven, which CI runners can reach.

Gradle needs JDK 17–21 and the Android SDK. A newer system JDK is fine as long as Gradle is
pointed at a supported one; see [`docs/HANDOFF.md`](docs/HANDOFF.md) §6 for the local setup.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# Unit tests (no device needed)
./gradlew testDebugUnitTest

# Static analysis
./gradlew lintDebug

# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Install on a connected device / emulator
# NOTE: replacing a CI-built APK fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE (different
# debug keystore) and needs `adb uninstall` first, which wipes saved profiles and the
# Keystore-held API key. See docs/HANDOFF.md §6 to rescue profiles beforehand.
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Sanitized logs
adb logcat | grep -i geoalign
```

### CI

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
