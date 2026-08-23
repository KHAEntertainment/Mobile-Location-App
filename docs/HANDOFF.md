# GeoAlign Browser — Session Handoff

> Purpose of this file: give a new (Claude Code) session everything it needs to pick up
> where we left off — **current status, the decisions and war stories that got us here, and the
> next logical steps** — without re-stating what's already documented. The architecture plan and
> honesty constraints live in [`ARCHITECTURE_PLAN.md`](ARCHITECTURE_PLAN.md); this doc does **not**
> repeat them. **⚠ The original engineering spec is NOT in the repo** — see §8 for what the `§n`
> code references actually point to. Last updated **2026-08-23**, repo at `main`.

---

## 1. What the app is (30 seconds)

A **sideloadable Android privacy browser** that aligns the browser-visible location signals
(geolocation, timezone, locale, UA) with the apparent exit of a VPN the **user is already
running themselves**. Three hard constraints that must never be violated in code, UI, or copy:

- It does **not** implement or operate a VPN.
- It does **not** change Android's system GPS.
- It makes **no anonymity guarantee** — it's a consistency tool, not a cloak.

Everything the page sees is virtualized inside an embedded WebView; the device itself is untouched.

---

## 2. Current status

Milestones **M1 (POCs), M2 (data foundation), M3 (browser)** are implemented, and the browser is
validated on-device: DuckDuckGo and Tinder both load, the injected location/timezone/locale
environment works, multi-tab works, and the live device switcher works.

**No open blocker.** The two that dominated the previous handoff are both resolved:

- **CI** was failing at runner startup (an account-level Actions problem, not code). Fixed by the
  user. A separate, genuine blocker was also cleared: `lintDebug` failed on a false-positive
  `PermissionImpliesUnsupportedChromeOsHardware` against the *removed* CAMERA permission
  (commit `d0029cd`).
- **The Tinder "portrait view" gate** is fixed (commit `58035e8`). The cause was not
  WebView-hostile detection: the WebView reported a **CSS viewport height of 0**, so
  `(orientation: landscape)` matched on every page. See
  [`TROUBLESHOOTING_WEBVIEW.md`](TROUBLESHOOTING_WEBVIEW.md) §1.

Native mode's browser identity was also made self-consistent with the device's real Chrome
(commit `c4a52e5`), which closed the deferred `Sec-CH-UA` request-header gap along the way.

**Builds are local now** — see [§6](#6-working-locally). A green gate is
`./gradlew testDebugUnitTest lintDebug assembleDebug`: currently **191 tests, 0 failures**.

## 3. Architecture at a glance (the non-obvious parts)

**Strict layering — keep this split for all new work:**

- `com.geoalign.core.*` — **pure, dependency-free, unit-tested** logic: reducers, models, policies.
  No Android imports. Examples: `core.readiness` (ReadinessReducer), `core.net`
  (LocalNetworkPolicy, UrlNormalizer, ExternalSchemePolicy), `core.tabs` (TabList/TabListReducer),
  `core.device` (DeviceProfile/DeviceProfiles, **NativeIdentity**), `core.model` (LocationProfile),
  `core.i18n`.
- `com.geoalign.data.*` — Android/IO glue: VPN/IP/geolocation repositories, `ProfileStore` +
  `JsonFileProfileStore`, `ProfileFactory`, `ReadinessService`, secure key storage.
- `com.geoalign.web.*` — WebView policy + injection: `web.policy` (BrowserWebViewClient,
  BrowserWebChromeClient), `web.environment` (EnvBundleCompiler, DeviceBundleCompiler).
- `com.geoalign.browser.*` — Compose UI: `MainActivity`, `ReadinessDashboard`, `ProfileEditor`,
  `BrowserScreen`.
- `com.geoalign.di.AppGraph` — **manual DI** (no Hilt/Dagger). New singletons wire in here.

**How the browser virtualizes the environment:** two `document-start` scripts are injected via
`WebViewCompat.addDocumentStartJavaScript` before any page script runs:

1. `assets/env_bundle.js` — geolocation shim, `navigator.language(s)`, `Intl`/`Date` timezone
   rendering, WebRTC relay-only. Compiled per active `LocationProfile` by `EnvBundleCompiler`.
2. `assets/device_bundle.js` — `navigator.userAgentData` + (for spoof presets only) screen /
   DPR / touch / platform. Compiled per `DeviceProfile` by `DeviceBundleCompiler`.

Both compilers are **pure token-substitution + block-builders with unit tests**; only
`compileFromAssets(context, …)` touches Android.

**Tabs:** one hardened WebView, N tabs. Per-tab page state is parked with
`WebView.saveState`/`restoreState` keyed by the pure `TabList` model's tab id. Only the active tab
is ever rendered (flat memory).

**Device modes:** `DeviceProfiles.NATIVE` ("This device", the default) presents the real hardware
geometry plus the identity of the **device's own Chrome**, derived at runtime: `NativeIdentity`
reproduces Chrome's *reduced* UA and `NativeUaMetadata` sets matching client hints via
`WebSettingsCompat.setUserAgentMetadata` (which also fixes the `Sec-CH-UA` request headers).
Native mode deliberately emits no `userAgentData` JS shim — see `TROUBLESHOOTING_WEBVIEW.md` §2.
Spoof presets (Pixel 8, Galaxy S24, iPhone 15 Pro/SE, desktop Chrome) override geometry + UA-CH;
they carry the spoofing that hostile sites may reject. Selection persists to
`LocationProfile.userAgentProfileId`.

**Persistence:** kotlinx.serialization JSON file + Android Keystore (AES-256/GCM). **Deliberately
no Room** (avoids an annotation processor and keeps CI simple).

---

## 4. Decision log — why things are the way they are

- **No Room / no DI framework.** kotlinx.serialization + Keystore + a hand-written `AppGraph`.
  Keeps the build fast and CI green without annotation processors.
- **`ipwho.is` for IP-geolocation.** Chosen keyless/HTTPS after `ipinfo` dropped free city-level geo.
- **Single active WebView.** The spec's model; memory stays flat as tabs grow.
- **Two-bundle document-start injection.** Location and device concerns stay separable and
  independently testable; the device bundle is swappable at runtime (a `ScriptHandler` is kept so
  the live device switcher can remove/re-add it).
- **HTTPS-only + hard security posture.** `usesCleartextTraffic=false`, `MIXED_CONTENT_NEVER_ALLOW`,
  Safe Browsing on, invalid TLS **cancelled** (no "proceed anyway"), local-network requests blocked
  at the WebView boundary (`LocalNetworkPolicy`), non-web schemes allow-listed
  (`ExternalSchemePolicy`), camera/mic/geolocation prompts denied.
- **"This device" native mode is the default (commit `24fb4c7`).** Sites like Tinder refuse
  embedded-WebView / inconsistent fingerprints; presenting the genuine device is the most
  compatible baseline. Spoof presets remain opt-in.
- **Conservative toolchain pin.** AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / webkit
  1.12.1 / SDK 35 / **minSdk 26**. Plan is to move to webkit 1.16 / API 36 *after* stable, not before.

**War stories (fixes found only on-device — worth knowing before you "re-improve" something):**

- **Timezone leak:** `Date.prototype.toString()` leaked the real zone (Pacific) while `Intl` said
  London. Fixed by overriding the `Date` string methods in `env_bundle.js`.
- **Edge-to-edge notch:** targetSdk 35 forces edge-to-edge; content drew under the cutout → wrapped
  in `safeDrawingPadding`.
- **Address bar:** `UrlNormalizer` turned `about:blank` into a search; fixed with an opaque-scheme rule.
- **White-on-white status bar:** edge-to-edge + light background made the clock invisible → system
  bar strips tinted with the primary color + light bar icons (commit `24e4fca`).
- **Embedded-WebView detection:** led to native mode (above).
- **Zero-height CSS viewport (the big one):** Compose's `AndroidView` leaves a child's own
  `LayoutParams` at `WRAP_CONTENT`, and WebView takes its CSS viewport height from `LayoutParams`
  rather than from the measured size — so `100vh` resolved to `0` and `(orientation: landscape)`
  matched on *every* page. This was the Tinder gate. It survived weeks of wrong theories
  (embedded-WebView rejection; an unhonoured `<meta viewport>`, commit `c8f9b55`) because nobody
  measured `100vh`. **Prefer a measurement over a plausible story** — the DevTools-over-adb method in
  `TROUBLESHOOTING_WEBVIEW.md` §3 turns that into a two-minute check.
- **Native mode contradicted itself:** it sent a truthful UA string beside hardcoded client hints
  claiming Chrome 126 / Android 14 on a Chrome 151 / Android 16 device. Real Chrome always agrees
  with itself, so cross-checking sites read the mismatch as spoofing. Real Chrome also sends a
  *reduced* UA (frozen `Android 10; K`) — stripping the `wv` marker is not enough.

---

## 5. Next logical steps (priority order)

1. **Refresh the on-device sanity pass** after the viewport fix — the whole layout was previously
   laid out against a zero-height viewport, so any CSS that looked subtly wrong is worth re-checking.
2. **Add a visible build/version stamp** (natural home: the "Site & privacy" sheet; a `BuildConfig`
   git-SHA field). Less urgent now that `adb install` makes the installed build knowable, but it
   still ends "which build am I on?" for good.
3. **Fold the device picker into `ProfileEditor`** (currently only in the browser toolbar).
4. **Consider shimming the remaining WebView tells** — `window.chrome`, `Notification` — but only
   against a *confirmed* detection. See `TROUBLESHOOTING_WEBVIEW.md` §4; this widens the
   fingerprint-spoofing surface, so it is not free.
5. **Toolchain upgrade path** (webkit 1.16 / API 36) once everything is stable and green.
6. **Remaining scope** not yet built — `ARCHITECTURE_PLAN.md` is the best in-repo list of intended
   scope, but the authoritative requirement list lives in the uncommitted spec (see §8); confirm
   with Billy before treating any `spec §n` as ground truth.

## 6. Working locally

This project was originally built remotely (cloud sandbox → GitHub → CI-built APK the user
sideloaded and screenshotted). It is now developed **locally on macOS**, which removes that entire
loop. Toolchain, installed via Homebrew:

| Component | Notes |
|---|---|
| `adb` (`android-platform-tools`) | device + DevTools access |
| `android-commandlinetools` | SDK root `/opt/homebrew/share/android-commandlinetools` |
| `platforms;android-35`, `build-tools;35.0.0` | matches `compileSdk = 35` |
| **OpenJDK 21** (`brew install openjdk@21`) | *not* the `temurin@21` cask — its `.pkg` installer needs an interactive sudo password |

The system default JDK is deliberately left alone; Gradle is pointed at 21 per invocation, because
a newer JDK is too new for Gradle 8.11.1 / AGP 8.7.3:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Signing.** CI and local builds use different debug keystores, so the first local install over a
  CI-built APK fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needs an `adb uninstall` — which
  **wipes saved profiles and the Keystore-held API key**. Profiles can be rescued first with
  `adb exec-out run-as com.geoalign.browser cat files/profiles.json` (debuggable builds only); the
  Keystore key cannot be exported by design and must be re-entered.
- **DevTools over adb** is the highest-leverage tool here — see
  [`TROUBLESHOOTING_WEBVIEW.md`](TROUBLESHOOTING_WEBVIEW.md) §3.
- **CI** is `.github/workflows/android.yml` (unit tests → lint → `assembleDebug` → rolling
  `latest-debug` prerelease APK). Treat workflow edits as high-care.

**Conventions to preserve:** ship in **small reviewable slices, each green before proceeding**; put
logic in `core.*` with unit tests and keep Android glue thin; never weaken the three honesty
constraints in §1.

## 7. Guardrails & gotchas

- **minSdk 26.** Don't use APIs above it without a guarded fallback.
- **Permissions:** only `INTERNET` + `ACCESS_NETWORK_STATE`. Location/camera/mic/wifi/bluetooth are
  removed in the manifest and their absence is **asserted by tests** — never add them.
- **DownloadManager** writes to public Downloads can silently no-op on API < 29 without storage
  permission (wrapped in `runCatching`, non-fatal; fine on modern devices).
- **Two GitHub accounts** are connected to the remote integration; only the one for GitHub user
  `43256680` can reach this private repo (the other 404s). Irrelevant once local.
- Keep the "does not operate the VPN / does not promise anonymity" line intact in the Site & privacy
  sheet and docs.

---

## 8. The spec, the `§n` references, and existing docs

**Read this before trusting any `§n` comment.** The code carries two *different* section-numbering
systems, and they are not interchangeable:

- **`spec §n`** → the original **engineering spec** — an uploaded ~31-section `.docx` that was the
  source material for the whole project. It is the authoritative source for exact requirement
  wording (e.g. the §3 no-VPN behavior), but it was **never committed to this repo**. It only exists
  in the project owner's (Billy's) hands. A `spec §n` reference therefore **cannot be resolved from
  the repo alone**.
- **`plan §n`** → [`docs/ARCHITECTURE_PLAN.md`](ARCHITECTURE_PLAN.md), which is a *derived*
  architecture plan (generated at M0 as a response to the spec) with its **own independent
  numbering**. It is **not** the spec and its `§n` do not map 1:1 to the spec's.

Until the spec is committed, treat the **code comments + `ARCHITECTURE_PLAN.md` as the surviving
evidence**, and **flag ambiguity rather than assert** exact spec wording. **Recommended fix:** ask
Billy for the original spec and commit it (e.g. `docs/ENGINEERING_SPEC.md`) so `spec §n` references
resolve to a real source of truth.

| File | What it covers |
|------|----------------|
| `docs/ARCHITECTURE_PLAN.md` | Derived architecture plan + honesty constraints; **its own numbering** (`plan §n`), not the spec |
| *(uncommitted)* engineering spec `.docx` | The real requirements + `spec §n` numbering — held by Billy, not in the repo |
| `docs/VALIDATION_M1.md` | M1 adversarial review findings + dispositions |
| `docs/POC_NOTES.md` | What each POC proves and how to read pass/fail |
| `docs/TROUBLESHOOTING_WEBVIEW.md` | The zero-height viewport bug, native-mode identity, **DevTools-over-adb method**, remaining WebView tells |
| `README.md` | Status, build/CI commands, toolchain versions |

---

## 9. Commit spine (milestone map for `git log`)

- `615e33b` M1: scaffold, POC harness, local-network + readiness logic, CI
- `e556a9d` M1 adversarial review fixes (WebRTC, tz leak, classifier gaps)
- `ade7a7f` M2 foundation: VPN/IP/geolocation repos + profile model
- `31573ec` M2 persistence: JSON ProfileStore + Android Keystore
- `a6b4e27` M2 readiness dashboard wired to ReadinessService
- `5a32afa` M2 "Match Browser to VPN" (live geo → active profile)
- `03d351a` M2 profile editor screen
- `d7e4ff4` → `24aa018` M3 slice 1: single-tab browser shell (+ UrlNormalizer opaque-scheme fix)
- `4746fc4` UX: step-ordered readiness actions
- `dd58ea3` M3 slice 2: multi-tab (single active WebView)
- `19c3c69` M3 slice 3: device emulation (UA + JS device signals)
- `6047861` M3 slice 4: finishing touches (SSL, schemes, downloads, clear-session)
- `24e4fca` UX: tint system bars readable + enable WebView debugging
- `24fb4c7` Device: "This device" native mode (default)
- `c8f9b55` Fix Tinder portrait gate: honor viewport meta (*did not fix it — wrong layer*)
- `409b883` docs: WebView troubleshooting + re-trigger CI
- `6186412` docs: session handoff
- `d0029cd` Fix lint false positive on the removed CAMERA permission (**unblocks CI**)
- `c4a52e5` **Native mode: real Chrome identity** (UA reduction + `setUserAgentMetadata`)
- `58035e8` **Fix zero-height CSS viewport** ← the actual Tinder fix
