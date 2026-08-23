# PROJECT_CONTEXT — GeoAlign Browser

Working context for AI-assisted development. Product/architecture detail lives in
`docs/ARCHITECTURE_PLAN.md`; session-to-session state lives in `docs/HANDOFF.md`.

## What this is

A sideloadable Android privacy browser that aligns browser-visible location signals with the
apparent exit of an **already-running, user-operated** VPN. It does not implement a VPN, does not
change Android's system GPS, and makes no anonymity guarantee.

## Current state (2026-08-23)

- `main` @ `0a098b2`. Last tag `v0.2.0` (now well behind `main`).
- The unit suite is **all JVM** and runs per edition — `testPlayDebugUnitTest` and
  `testCommunityDebugUnitTest`. Both are green in CI on every push; read the current counts from a
  workflow run rather than from this file, which cannot keep up with them. There are still **no
  instrumentation tests** — see the holes list.
- Repo is **public**: https://github.com/KHAEntertainment/Mobile-Location-App

### Milestones (numbering from `docs/ARCHITECTURE_PLAN.md` §14)

| Milestone | State |
|---|---|
| M1 foundations — scaffold, `LocalNetworkPolicy`, `ReadinessReducer`, `env_bundle.js` | done |
| M2 data — VPN/IP/geo repos, `ReadinessService`, profile storage, profile editor | done |
| M3 browser — multi-tab hardened WebView, device emulation, SSL/scheme policy, downloads | done |
| M6 UX & reliability — slice 1: design system, pure readiness presenter, `AlignmentChecker` | done |
| M6 slice 2 — WebView extraction, flavors, live monitoring, capability gate, diagnostics | done |
| M7 store readiness — API 36, About/Privacy, release gates | **next** |
| M8 daily-use / M9 partner directory | planned |

## Architecture conventions

- `core/` = pure Kotlin, no Android imports, unit-tested. `data/` = IO. `ui/state/` = pure
  presentation logic. `web/` = WebView policy and environment injection.
- **Business logic never lives inside a `@Composable`.** The 759s stale-profile bug (`3d3108b`)
  existed because it did, where no test could reach it. New state machines go in `core/` with JVM
  tests; composables render what a pure presenter returns.
- Manual DI through `di/AppGraph.kt` — no framework.
- Capability facts are produced once and shared; no surface re-queries `WebViewFeature` on its own,
  and **no protection is reported active merely because it was requested**.
- Distribution differences flow through an injected `DistributionCapabilities`, never
  `if (BuildConfig.FLAVOR)` at call sites.

## Known holes (re-verified 2026-08-23 against `0a098b2` — do not assume fixed)

Still true:

- **There is no `app/src/androidTest` source set.** `androidx.test.ext:junit` and `espresso-core`
  are declared in `app/build.gradle.kts` and are inert. Every test in this project is JVM.
- **CI runs `./gradlew lintDebug ... || true`** — lint has never been able to fail a build. This is
  not theoretical: #12 introduced three `ScopedStorage` warnings and CI would have gone green on
  them. Removing the `|| true` is M7 work.
- The **merged** manifest contains a third permission,
  `com.geoalign.browser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, merged by `androidx.core` at
  `protectionLevel="signature"`. Harmless, but an M7 test asserting an exact set of two will fail.
- `ReadinessService.ipStackDivergence` is still hardcoded `false`. The reducer consumes it
  correctly; nothing produces a real value.
- `onBlocked` is wired in `LocalNetworkInterceptor` and `BrowserWebViewClient` and **still reaches
  no UI**. Blocked local-network requests are invisible to the user.
- `ServiceWorkerControllerCompat` is named in KDoc only. `WebViewCapabilities.serviceWorkerControl`
  is carried as a fact with **no consumer**, and the Site & Privacy sheet deliberately never claims
  local-network filtering is active because of it. A test fails loudly when that changes.
- No `onShowFileChooser` and no `setSupportMultipleWindows` — file uploads and OAuth popups do not
  work, which makes otherwise-compatible sites look broken.
- **Downloads silently fail on API 26–28** (issue #16). `setDestinationInExternalPublicDir` needs
  `WRITE_EXTERNAL_STORAGE`, which this app never holds and now explicitly removes, and the
  `SecurityException` is swallowed by `runCatching`. `minSdk` is 26.
- Every `§n` reference in the code points at an **external spec that was never committed**.
  `docs/ARCHITECTURE_PLAN.md` is a response to it with independent numbering.
- Tinder still gates the browser. The viewport hypothesis was falsified — the fix shipped in
  `c8f9b55` and the gate persists. Next step needs `chrome://inspect` on a physical device.
  **Parked**, not blocking.

Closed during the M6 reliability block, listed so a returning reader does not re-report them:
`LICENSE` (MPL 2.0, #1) · the false "asserted absent by test" README claim (#1) ·
`transportUpdates()` having no consumer (#5) · `WebView.destroy()` and script-handler cleanup, plus
`onRenderProcessGone` (#3) · POC diagnostics injecting a hardcoded London profile (#8) ·
six missing defensive permission removals (#12) · `matchedOn` reported as `COUNTRY` when country
was never compared (#19).

## Working rules

- Task tracking: **GitHub Issues**, canonical. One issue per work item; branch
  `feature/<issue>-<slug>`; PR closes it.
- No code changes in the main conversation — worker agents in isolated worktrees, merged via PR.
- Toolchain: JDK 17–21, `ANDROID_HOME` set. Before any PR, run **both** editions —
  `./gradlew testPlayDebugUnitTest testCommunityDebugUnitTest` — because they compile different
  source sets. There is no unflavored `testDebugUnitTest` or `assembleDebug` any more.
- The repo ruleset applies `non_fast_forward` to branches: **you cannot force-push, so you cannot
  publish a rebase.** Merge `main` in instead; everything is squash-merged, so the merge commit
  disappears on landing.
- Replacing a CI-built APK needs `adb uninstall` first (different debug keystore), which wipes
  saved profiles and the Keystore API key. See `docs/HANDOFF.md` §6.
