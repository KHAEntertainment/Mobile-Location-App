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

Milestones **M1 (POCs), M2 (data foundation), M3 (browser)** are all implemented and were green
in CI historically. The browser is functional and has been validated on-device: DuckDuckGo loads,
the injected location/timezone/locale environment works, multi-tab works, and the live device
switcher works.

**Immediate blocker (as of this handoff):** CI runs **#22 (`c8f9b55`)** and **#23 (`409b888`)**
both **failed at runner startup (~3–5s, empty logs, before any build step)**. That signature is an
account-level Actions problem — almost certainly **billing / exhausted Actions minutes / a spending
limit** on the private repo, *not* a code failure. The user is re-running the workflow. Until a
build goes green, no fresh APK is produced. **Last successful APK = build #21, commit `24fb4c7`**
("This device" native mode).

**Open functional issue:** Tinder still shows its **"Catch Us On The Flip Side — enjoy matching up
from a portrait view"** gate. This is Tinder's own client-side gate, not a crash. A viewport fix
(`useWideViewPort` / `loadWithOverviewMode`, commit `c8f9b55`) is committed but **unverified**
because it hasn't been allowed to build. See [`TROUBLESHOOTING_WEBVIEW.md`](TROUBLESHOOTING_WEBVIEW.md).

---

## 3. Architecture at a glance (the non-obvious parts)

**Strict layering — keep this split for all new work:**

- `com.geoalign.core.*` — **pure, dependency-free, unit-tested** logic: reducers, models, policies.
  No Android imports. Examples: `core.readiness` (ReadinessReducer), `core.net`
  (LocalNetworkPolicy, UrlNormalizer, ExternalSchemePolicy), `core.tabs` (TabList/TabListReducer),
  `core.device` (DeviceProfile/DeviceProfiles), `core.model` (LocationProfile), `core.i18n`.
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

**Device modes:** `DeviceProfiles.NATIVE` ("This device", the default) presents the *real* hardware
geometry with a cleaned Chrome UA (strips the `; wv` embedded-WebView marker). Spoof presets
(Pixel 8, Galaxy S24, iPhone 15 Pro/SE, desktop Chrome) override geometry + UA-CH; they carry the
spoofing that hostile sites may reject. Selection persists to `LocationProfile.userAgentProfileId`.

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
- **Orientation/viewport gate:** WebView default `useWideViewPort=false` lays responsive sites out
  at the raw width, so `(orientation: landscape)` can read true → the current (unverified) Tinder fix.

---

## 5. Next logical steps (priority order)

1. **Unblock CI** (Actions billing/minutes) → re-run → confirm a green build and fresh
   `latest-debug` APK.
2. **Verify the viewport fix against Tinder.** If it still gates, use `chrome://inspect` (enabled on
   debug builds) per `TROUBLESHOOTING_WEBVIEW.md` to compare our WebView's signals against real
   Chrome — turn guessing into evidence.
3. **Native `Sec-CH-UA` header swap** via `WebSettingsCompat.setUserAgentMetadata` — the one
   deferred device-emulation piece. Client-side JS identity is handled; the HTTP request headers
   still carry the WebView identity. Do this only if the inspector confirms a site gates on headers.
   (API shape wasn't compile-verifiable in the remote sandbox — a local session can build and check.)
4. **Fold the device picker into `ProfileEditor`** (currently only in the browser toolbar).
5. **Add a visible build/version stamp** (natural home: the "Site & privacy" sheet) so "which build
   am I on?" stops being guesswork. Consider a `BuildConfig` git-SHA field.
6. **Refresh `README.md`** — its Status section still says "Milestone 1 (POCs)".
7. **Toolchain upgrade path** (webkit 1.16 / API 36) once everything is stable and green.
8. **Remaining scope** not yet built — `ARCHITECTURE_PLAN.md` is the best in-repo list of intended
   scope, but the authoritative requirement list lives in the uncommitted spec (see §8); confirm
   with Billy before treating any `spec §n` as ground truth.

---

## 6. Working in a local Claude Code session (what changes)

This project was built **remotely from a mobile/Cowork session**: code authored in a cloud sandbox,
pushed to `KHAEntertainment/Mobile-Location-App` via a GitHub integration, with CI building and
publishing a rolling `latest-debug` prerelease APK. A local Claude Code session simplifies most of
that:

- **You have the repo checked out.** Build and test locally:
  `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, `./gradlew assembleDebug`
  (APK → `app/build/outputs/apk/debug/app-debug.apk`). No remote push integration needed — commit
  and push with plain `git`.
- **`chrome://inspect` is trivial** over USB with a debug build — use it liberally for WebView work.
- **CI** is `.github/workflows/android.yml` (push to `main`/`feature/**`, or run manually). It runs
  unit tests → lint → `assembleDebug` → overwrites the `latest-debug` prerelease APK. **The agent
  cannot modify files under `.github/workflows/` via the API** — that has always required the human
  to edit the workflow directly. In a local session with push rights this constraint may not apply,
  but treat workflow changes as high-care.
- **No in-app version stamp yet** — see step 5 above; until then, identify a build by the Device
  menu having "This device (recommended)" at the top (= build #21+) and readable purple status bars.

**Conventions to preserve:** ship in **small reviewable slices, each green in CI before proceeding**;
put logic in `core.*` with unit tests and keep Android glue thin; never weaken the three honesty
constraints in §1.

---

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
| `docs/TROUBLESHOOTING_WEBVIEW.md` | WebView-hostile sites (Tinder), native mode, viewport fix, `chrome://inspect` checklist |
| `README.md` | Build/CI commands (⚠ Status section is stale — says Milestone 1) |

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
- `24fb4c7` **Device: "This device" native mode (default)** ← last green APK (build #21)
- `c8f9b55` Fix Tinder portrait gate: honor viewport meta (**unbuilt — CI blocked**)
- `409b888` docs: WebView troubleshooting + re-trigger CI (**unbuilt — CI blocked**)
