# AGENTS.md

Guidance for AI agents working on this repository.

## Project Overview

**GeoAlign Browser (MVP)** is a sideloadable Android privacy browser that aligns browser-visible
location signals (geolocation, timezone, locale, user-agent identity) with the apparent exit of an
already-running, user-operated VPN. It does **not** implement a VPN, does **not** change Android's
system GPS, and makes no anonymity guarantee.

Current status: **Milestones 1–3 implemented** (POCs, data foundation, browser) and validated
on-device. Last green CI build = **#21, commit `24fb4c7`** ("This device" native mode). The Tinder
viewport fix (`c8f9b55`) is committed but **never built** — CI runs #22–#24 all failed at runner
startup due to account-level Actions billing (payment failure / spending limit), not code.

## Tech Stack

- **Language:** Kotlin 2.0.21 (official code style), JavaScript (injected WebView bundles), XML
- **Platform:** Android — single-activity Jetpack Compose app
  - compileSdk/targetSdk 35 · minSdk 26 · Java/JVM target 17
- **Build:** Gradle 8.11.1 (Kotlin DSL) + version catalog (`gradle/libs.versions.toml`)
- **Key libraries:**
  - androidx.webkit **1.12.1** (`addDocumentStartJavaScript`, UA-CH override — backbone of the project)
  - Jetpack Compose BOM 2024.12.01 / Material3
  - OkHttp 4.12.0 · kotlinx-serialization-json 1.7.3 · kotlinx-coroutines-android 1.9.0
  - Room 2.6.1 is declared in the catalog but **deliberately unused** (JSON-file persistence chosen for MVP)
- **CI:** GitHub Actions (`.github/workflows/android.yml`), JDK 17 Temurin, triggers on push to `main` or `feature/**`

## Commands

```bash
./gradlew testDebugUnitTest    # Unit tests (no device needed)
./gradlew lintDebug            # Android Lint (CI runs with "|| true", non-blocking)
./gradlew assembleDebug        # Debug APK -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -i geoalign  # Sanitized logs
```

No environment variables or API keys are required to build. Release signing material is never committed
(`*.jks` / `*.keystore` are gitignored).

## Architecture

Single Gradle module `:app`. Layered, interface-driven design per `docs/ARCHITECTURE_PLAN.md`.

Data flow:
VPN transport → internet reachability → effective public IP → IP geolocation → selected profile →
readiness state → compiled JS bundle → hardened WebView.

| Layer | Package(s) under `app/src/main/java/com/geoalign/` | Purpose |
|---|---|---|
| UI | `browser/` | `MainActivity` + Compose screens (`ReadinessDashboard`, `ProfileEditor`, `BrowserScreen`) with in-app nav via private `Screen` enum |
| Core (pure) | `core/readiness`, `core/net`, `core/model`, `core/device`, `core/i18n`, `core/tabs` | Dependency-free domain logic; e.g. `ReadinessReducer.reduce(ReadinessInputs): ReadinessState`. Exhaustively unit-tested without Android |
| Data | `data/vpn`, `data/net`, `data/geolocation`, `data/profiles`, `data/settings`, `data/readiness` | Implementations: VPN detection via `ConnectivityManager`; effective IP via OkHttp (ipify primary); geolocation via ipwho.is (`IpWhoIsProvider`, keyless) with optional keyed ipinfo.io parser in `GeoResponseParser`; profiles in JSON file; secrets via Android Keystore AES-256 |
| Web | `web/environment`, `web/policy` | Compiles `BrowserEnvironment` into document-start JS bundles injected before any page script; policy enforcement (`LocalNetworkInterceptor`, `BrowserPermissionPolicy` denies native geolocation/camera/mic) |
| DI | `di/AppGraph.kt` | Manual DI factory `object` — deliberately no Hilt/Koin |

### Key Files

- Entry point: `app/src/main/java/com/geoalign/browser/MainActivity.kt`
- DI wiring: `app/src/main/java/com/geoalign/di/AppGraph.kt`
- Manifest: `app/src/main/AndroidManifest.xml` — only `INTERNET` + `ACCESS_NETWORK_STATE`;
  defensive `tools:node="remove"` strips location/wifi/camera/mic permissions merged from deps;
  `allowBackup=false`, `usesCleartextTraffic=false`
- JS assets: `app/src/main/assets/env_bundle.js` (placeholder substitution: `__LAT__ __LNG__ __ACC__ __TZ__ __LANG__ __LANGS__`),
  `device_bundle.js`, `poc.html`

### External Integrations

All HTTPS, keyless by default:
- IP check: `https://api.ipify.org?format=json` (primary), icanhazip / Cloudflare trace (secondary, IPv4/IPv6 divergence detection)
- Geolocation: `https://ipwho.is/{ip}` (64 KiB response cap, strict timeouts)

## Code Conventions

- Kotlin official code style (`kotlin.code.style=official` in `gradle.properties`)
- Strict **pure core vs impure data separation**: `core.*` packages have zero framework imports;
  data classes take clock/network as injectable constructor parameters (e.g. `IpWhoIsProvider(client, clock)`)
- Interface + implementation naming: `ProfileStore`/`JsonFileProfileStore`,
  `SecureKeyStore`/`AndroidKeystoreSecureKeyStore`, `EffectiveIpRepository`/`OkHttpEffectiveIpRepository`, etc.
- Manual constructor injection; test fakes instead of mocking libraries
- Tests mirror the main source tree under `app/src/test/java/com/geoalign/...` with `*Test.kt` suffix (JUnit 4)
- All dependencies resolved through the version catalog (`libs.*`) — never hardcode coordinates
- KDoc comments reference spec sections and honesty constraints ("Accepted limitation",
  "never claim a protection…") — preserve this tone in new code

## Security & Honesty Posture

- Never claim a protection the app does not provide; document accepted limitations explicitly
- No API keys in source or BuildConfig; secrets belong in Android Keystore (AES-256, encrypted at rest)
- No cleartext traffic; WebView hardened with feature gating via `WebViewFeature.isFeatureSupported(...)`
- Conservative versioning: pin known-good versions first (webkit 1.12.1 / SDK 35);
  upgrade later (webkit 1.16.0 / API 36 planned)

## Documentation Map

- `docs/HANDOFF.md` — **start here**: session handoff with status, decision log, war stories, prioritized next steps (upstream `main` @ `6186412`; sync local clone to get it)
- `README.md` — overview, build instructions, toolchain versions, permission policy (⚠ its Status section is stale — says "Milestone 1")
- `docs/ARCHITECTURE_PLAN.md` — full plan: milestones, proposed 6-module split of ~19 spec modules, SDK/provider rationale
- `docs/VALIDATION_M1.md` — adversarial security review (12 findings/dispositions)
- `docs/POC_NOTES.md` — what each POC 1–5 verifies
- `docs/TROUBLESHOOTING_WEBVIEW.md` — WebView-hostile sites handling, remote debugging

## Git Notes

- Upstream: `https://github.com/KHAEntertainment/Mobile-Location-App.git`
- CI triggers on push to `main` or `feature/**`
