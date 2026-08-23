# Dependency and license inventory

Every third-party component this project depends on, with the exact version in use and the
license it carries.

**How this was produced.** Versions are read from
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) and the configuration each dependency
is attached to is read from [`app/build.gradle.kts`](../app/build.gradle.kts). Nothing here is
recalled from memory. When you change a version in the catalog, update the matching row.

**Verified against the tree on 2026-08-23.** Gradle does not generate this file, so it can drift.
[`CONTRIBUTING.md`](../CONTRIBUTING.md) makes updating it part of any dependency change.

---

## 1. What ships in the APK

These are on the `implementation` configuration and are linked into a distributed build.

| Component | Version | License | Why it is here |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.15.0 | Apache-2.0 | Kotlin extensions over the Android framework |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | Lifecycle-aware coroutine scopes |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | Apache-2.0 | ViewModel access from Compose |
| `androidx.activity:activity-compose` | 1.9.3 | Apache-2.0 | Single-activity Compose host |
| `androidx.compose:compose-bom` | 2024.12.01 | Apache-2.0 | BOM pinning every Compose artifact below |
| `androidx.compose.ui:ui` | via BOM | Apache-2.0 | Compose UI runtime |
| `androidx.compose.ui:ui-graphics` | via BOM | Apache-2.0 | Compose graphics primitives |
| `androidx.compose.ui:ui-tooling-preview` | via BOM | Apache-2.0 | `@Preview` annotations |
| `androidx.compose.material3:material3` | via BOM | Apache-2.0 | Material 3 components |
| `androidx.webkit:webkit` | 1.12.1 | Apache-2.0 | Document-start script injection, UA-CH override — the backbone of environment virtualization |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 | HTTPS client for the IP and geolocation repositories |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | Apache-2.0 | Profile persistence (chosen over Room — see `docs/HANDOFF.md` §4) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache-2.0 | Android main-dispatcher coroutines |
| Kotlin standard library | 2.0.21 | Apache-2.0 | Pulled in by the Kotlin Android plugin |

**Notable transitive dependency:** OkHttp 4.12.0 brings `com.squareup.okio:okio` (Apache-2.0) and
the Kotlin stdlib. It is Apache-2.0 throughout; no copyleft component enters the APK through it.

## 2. Debug builds only

| Component | Version | License | Configuration |
|---|---|---|---|
| `androidx.compose.ui:ui-tooling` | via BOM 2024.12.01 | Apache-2.0 | `debugImplementation` |

Not present in a release build.

## 3. Tests only

Never linked into a distributed build.

| Component | Version | License | Configuration |
|---|---|---|---|
| `junit:junit` | 4.13.2 | **EPL-1.0** | `testImplementation` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | Apache-2.0 | `testImplementation` |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 | `androidTestImplementation` |
| `androidx.test.espresso:espresso-core` | 3.6.1 | Apache-2.0 | `androidTestImplementation` |

JUnit 4 is the only non-Apache license in the tree. EPL-1.0 is a weak, file-level copyleft; because
JUnit is a test-scope dependency that is not modified, not redistributed, and not linked into the
APK, it imposes no obligation on a release build. If JUnit is ever vendored or shipped, this
determination has to be revisited.

**Honest note on the `androidTest` rows:** those two dependencies are declared in
`app/build.gradle.kts`, but **no `app/src/androidTest` directory exists** — there are no
instrumentation tests in this repository. The declarations are inert. They are listed because they
are in the build file, not because they run.

## 4. Build-time tooling

Not linked into the application.

| Component | Version | License | Source |
|---|---|---|---|
| Android Gradle Plugin (`com.android.application`) | 8.7.3 | Apache-2.0 | `libs.versions.toml` |
| Kotlin Android plugin (`org.jetbrains.kotlin.android`) | 2.0.21 | Apache-2.0 | `libs.versions.toml` |
| Kotlin serialization plugin | 2.0.21 | Apache-2.0 | `libs.versions.toml` |
| Compose compiler plugin | 2.0.21 | Apache-2.0 | `libs.versions.toml` |
| Gradle | 8.11.1 | Apache-2.0 | `gradle/wrapper/gradle-wrapper.properties` |

The Android SDK itself (compileSdk/targetSdk 35, minSdk 26) is obtained through the Android SDK
Manager under the Android Software Development Kit License Agreement. It is a developer-machine
prerequisite, not a redistributed component.

## 5. Declared in the catalog but not used

**None.** Every alias in `gradle/libs.versions.toml` is referenced by a build script, and every
`[versions]` key is reachable from a `[libraries]` or `[plugins]` entry.

This section previously listed three Room artifacts (`room-runtime`, `room-ktx`, `room-compiler` at
2.6.1). They were declared in the catalog but referenced nowhere, because Room was dropped in favour
of kotlinx.serialization plus Android Keystore to avoid an annotation processor
(`docs/HANDOFF.md` §4). They were never in the APK. #11 removed them from the catalog along with
their shared `room` version key, so there is no longer a stale alias inviting someone to
`implementation(libs.room.runtime)` and quietly reintroduce the dependency the project deliberately
declined.

If you add a catalog entry, add the build-script reference in the same change. An entry with no
consumer belongs in neither this file nor the catalog.

## 6. Runtime network services

Not software dependencies — outbound HTTPS calls the app makes at runtime. They are listed here
because they are third-party surfaces a reviewer should know about, and because each is governed by
its own terms of service rather than by a software license.

| Service | Purpose | Key required |
|---|---|---|
| `api.ipify.org` | Effective public IP (primary) | No |
| `icanhazip.com` | Effective public IP (secondary) | No |
| `ipwho.is` | IP → city / lat-long / IANA timezone | No |
| `ipinfo.io` Core | Optional higher-quality geolocation and VPN/hosting classification | Yes — user-supplied, stored in Android Keystore |

`ipwho.is` is the default; `ipinfo.io` dropped free city-level geolocation, which is why it is the
optional paid upgrade rather than the default (`docs/ARCHITECTURE_PLAN.md` §6). No analytics,
crash-reporting, advertising, or telemetry SDK is present in this project.

## 7. Platform component, not a dependency

Web content renders in the **Android System WebView** installed on the host device. It is an
operating-system component, is not bundled with or distributed by this application, and carries its
own licensing. Its version varies by device and is not controlled by this project — which is
precisely why `WebViewFeature.isFeatureSupported(...)` gates every capability at runtime instead of
being inferred from the OS level.

## 8. License obligations summary

- **Apache-2.0** (nearly everything): retain copyright and license notices, and state significant
  changes to any modified file. Satisfied by [`NOTICE`](../NOTICE); no Apache-licensed dependency is
  modified by this project.
- **EPL-1.0** (JUnit 4, test scope): source-availability obligations attach to distribution of the
  EPL-covered code. Not triggered — JUnit is not redistributed.
- **MPL-2.0** (this project): file-level copyleft on this project's own source. See
  [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the per-file header policy that carries it.

No component in this tree is licensed under the GPL, LGPL, or AGPL.
