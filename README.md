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
  virtualization, spec §11–13). The `poc.html` harness that exercised the POCs was removed in
  issue #8; the diagnostics screen now measures the production browser instead.
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

# Unit tests (no device needed). Every task below is per-edition — see ## Editions; there is
# no unflavored `testDebugUnitTest` / `assembleDebug` any more.
./gradlew testPlayDebugUnitTest testCommunityDebugUnitTest

# Static analysis
./gradlew lintPlayDebug lintCommunityDebug

# Debug APKs -> app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
./gradlew assemblePlayDebug assembleCommunityDebug

# Install on a connected device / emulator. Both editions can be installed at once; the
# community one is the complete build and the usual choice for sideloading.
# NOTE: replacing a CI-built APK fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE (different
# debug keystore) and needs `adb uninstall` first, which wipes saved profiles and the
# Keystore-held API key. See docs/HANDOFF.md §6 to rescue profiles beforehand.
adb install -r app/build/outputs/apk/community/debug/app-community-debug.apk

# Sanitized logs
adb logcat | grep -i geoalign
```

### CI

Push to `main` or any `feature/**` branch, or run the **Android CI** workflow manually. It runs one
matrix leg per edition — for each of `play` and `community` it 1. runs that edition's unit tests,
2. lints, 3. builds that edition's debug APK, and 4. uploads it as a downloadable artifact
(`geoalign-play-debug-apk` / `geoalign-community-debug-apk`). Download one from the workflow run's
*Artifacts* section and sideload it — no local Android Studio required. A green `community` leg says
nothing about `play`: the two compile different source sets, which is the point of the split.

## Key toolchain versions

- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01
- `androidx.webkit:webkit:1.12.1` (document-start injection, UA-CH override)
- compileSdk/targetSdk 35, **minSdk 26**

> Note: the plan discusses moving to webkit 1.16.0 / API 36 once a green CI build is established.
> This scaffold pins conservative, known-good versions first so CI goes green, then upgrades.

## Permissions

Only `INTERNET` and `ACCESS_NETWORK_STATE`. Every permission category the architecture plan rules
out — location, wifi-state, bluetooth/nearby, activity-recognition, camera, microphone, contacts,
phone-state, broad storage, and local-network — is explicitly removed in the manifest with
`tools:node="remove"`, so a dependency cannot merge one back in. The app must function without any
location permission.

The merged manifest also carries `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a
signature-level permission scoped to this app's own package that `androidx.core` adds so it can
register unexported runtime receivers. It grants nothing to any other app.

> **No automated test asserts their absence.** There is no `app/src/androidTest` source set in this
> repository yet, so the claim rests on the manifest, not on a test. The assertion against the
> merged manifest is scheduled for the M7 release-gate work; until then, verify by reading
> [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml).

## Editions

Two product editions ship from this tree, `play` and `community`, as Gradle product flavors. Build
them with `assemblePlayDebug` / `assembleCommunityDebug`. `community` carries the applicationId
suffix `.community` (`com.geoalign.browser.community`) and the launcher label "GeoAlign Community",
so both editions install and run side by side on one device.

The **Device identity** row below is enforced structurally, not by a setting. The spoof presets live
in `app/src/community/java/com/geoalign/core/device/ExperimentalDeviceProfiles.kt`, a file with no
counterpart in `app/src/play` — the Play variant is not compiled with it, so the preset data is not
in the Play artifact at all rather than being present and hidden. `testPlayDebugUnitTest` asserts
this by scanning the variant's compiled bytecode. The remaining rows describe capabilities carried
by an injected `DistributionCapabilities`. **`Diagnostics` is consumed as of issue #8** — the
readiness screen offers no route to it on `play`. **`Partner directory` is still declared and not
consumed by any surface**, so treat that row as the definition later work codes to, not as behaviour
you can observe today.

Both editions share the same core: the same alignment engine, the same hardened WebView, the same
three constraints in the section above, and the same `INTERNET` + `ACCESS_NETWORK_STATE` permission
set. Neither edition implements a VPN, and neither makes an anonymity claim.

| | `play` | `community` |
|---|---|---|
| **Posture** | Conservative. Ships only what survives store review and is safe for a first-time user. | Everything, including the sharp edges. |
| **Distribution** | Google Play (intended) | Sideloaded APK / GitHub Releases |
| **Device identity** | **Initially "This device" only** — the real hardware identity, no spoof presets. | "This device" plus the full set of experimental device profiles (Pixel, Galaxy, iPhone, desktop Chrome). |
| **Diagnostics** | User-facing readiness only. No route to the diagnostics screen exists. | Full developer diagnostics — the compatibility report, measured through the production WebView configuration, with per-check `PASS` / `WARN` / `N/A` / `FAIL` and a copyable sanitized report. |
| **Experimental features** | Off. Nothing behind an unverified flag reaches this edition. | Where experimental work lands first. |
| **Partner directory** | Planned addition, later — a curated directory of VPN providers. Not present at first release. | Not applicable. |

The reason for the split is the spoof presets: presenting a device identity other than the real one
is exactly the behaviour an app-store reviewer scrutinises, while it is the whole point of the tool
for a user who sideloads deliberately. Rather than weaken the tool, `play` starts narrow and
`community` stays complete.

**Implementation note for contributors:** edition differences flow through the
`DistributionCapabilities` value injected by `AppGraph.distributionCapabilities()`, never through
`if (BuildConfig.FLAVOR)` at a call site. Where a difference means code must be *absent* rather than
inert — the device presets — put the code in a flavor source set and let the other flavor not have
it; a capability flag describes that fact, it does not create it. See
[`CONTRIBUTING.md`](CONTRIBUTING.md) §5.

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
