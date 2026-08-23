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

Only `INTERNET` and `ACCESS_NETWORK_STATE`. Location, wifi, camera, and microphone permissions are
explicitly removed in the manifest with `tools:node="remove"`, so a dependency cannot merge one back
in. The app must function without any location permission.

> **No automated test asserts their absence.** There is no `app/src/androidTest` source set in this
> repository yet, so the claim rests on the manifest, not on a test. The assertion against the
> merged manifest is scheduled for the M7 release-gate work; until then, verify by reading
> [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml).

## Editions (planned — not in the build yet)

Two product editions are planned, `play` and `community`. **Neither exists in the build today** —
`app/build.gradle.kts` declares no `productFlavors`, and every build produced from this tree right
now is the single unflavored app. This section documents the intended split so that work landing
against it has one definition to code to; treat every row below as a plan, not as behaviour you can
observe.

Both editions share the same core: the same alignment engine, the same hardened WebView, the same
three constraints in the section above, and the same `INTERNET` + `ACCESS_NETWORK_STATE` permission
set. Neither edition implements a VPN, and neither makes an anonymity claim.

| | `play` | `community` |
|---|---|---|
| **Posture** | Conservative. Ships only what survives store review and is safe for a first-time user. | Everything, including the sharp edges. |
| **Distribution** | Google Play (intended) | Sideloaded APK / GitHub Releases |
| **Device identity** | **Initially "This device" only** — the real hardware identity, no spoof presets. | "This device" plus the full set of experimental device profiles (Pixel, Galaxy, iPhone, desktop Chrome). |
| **Diagnostics** | User-facing readiness only. | Full developer diagnostics — what the browser actually exposes, per-signal pass/fail, and the injection-verification surface. |
| **Experimental features** | Off. Nothing behind an unverified flag reaches this edition. | Where experimental work lands first. |
| **Partner directory** | Planned addition, later — a curated directory of VPN providers. Not present at first release. | Not applicable. |

The reason for the split is the spoof presets: presenting a device identity other than the real one
is exactly the behaviour an app-store reviewer scrutinises, while it is the whole point of the tool
for a user who sideloads deliberately. Rather than weaken the tool, `play` starts narrow and
`community` stays complete.

**Implementation note for contributors:** when these flavors do land, edition differences flow
through an injected `DistributionCapabilities` value, never through `if (BuildConfig.FLAVOR)` at a
call site. See [`CONTRIBUTING.md`](CONTRIBUTING.md) §5.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the toolchain, the test gate, the architecture
conventions, and the MPL source-file header policy. Security issues go through the private route in
[`SECURITY.md`](SECURITY.md), not the public issue tracker.

Every third-party dependency and its license is inventoried in
[`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md).

## License

GeoAlign Browser is licensed under the **Mozilla Public License 2.0**. The full text is in
[`LICENSE`](LICENSE), and attribution for third-party components is in [`NOTICE`](NOTICE).

MPL 2.0 is a file-level copyleft: you may use these files in a larger work under your own terms, but
modifications to MPL-covered files stay under the MPL and must be made available in source form.
This project carries plain MPL 2.0 with no "Incompatible With Secondary Licenses" notice, so it
remains compatible with the GPL, LGPL, and AGPL as Secondary Licenses.
