# PROJECT_CONTEXT — GeoAlign Browser

Working context for AI-assisted development. Product/architecture detail lives in
`docs/ARCHITECTURE_PLAN.md`; session-to-session state lives in `docs/HANDOFF.md`.

## What this is

A sideloadable Android privacy browser that aligns browser-visible location signals with the
apparent exit of an **already-running, user-operated** VPN. It does not implement a VPN, does not
change Android's system GPS, and makes no anonymity guarantee.

## Current state (2026-08-23)

- `main` @ `d643c06`, tagged `v0.2.0`. Working tree clean.
- **191 unit tests, 0 failures.** All JVM; no instrumentation tests exist.
- Repo is **public**: https://github.com/KHAEntertainment/Mobile-Location-App

### Milestones (numbering from `docs/ARCHITECTURE_PLAN.md` §14)

| Milestone | State |
|---|---|
| M1 foundations — scaffold, `LocalNetworkPolicy`, `ReadinessReducer`, `env_bundle.js` | done |
| M2 data — VPN/IP/geo repos, `ReadinessService`, profile storage, profile editor | done |
| M3 browser — multi-tab hardened WebView, device emulation, SSL/scheme policy, downloads | done |
| M6 UX & reliability — slice 1: design system, pure readiness presenter, `AlignmentChecker` | done |
| M6 slice 2 — WebView extraction, flavors, live monitoring, capability gate, diagnostics | **active** |
| M7 store readiness / M8 daily-use / M9 partner directory | planned |

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

## Known holes (verified 2026-08-23, do not assume fixed)

- No `LICENSE` on a public repo — default all-rights-reserved.
- README claims permissions are "asserted absent by test". **No such test exists**; there is no
  `app/src/androidTest` directory at all.
- CI runs `./gradlew lintDebug ... || true` — lint has never been able to fail a build.
- `VpnStatusRepository.transportUpdates()` has zero production callers.
- `ReadinessService.ipStackDivergence` is hardcoded `false`.
- `onBlocked` is wired in both policy clients and discarded at every call site.
- No `WebView.destroy()`, `onRenderProcessGone`, `onShowFileChooser`, `setSupportMultipleWindows`,
  or `ServiceWorkerControllerCompat` anywhere in the tree.
- ~~Diagnostics injects a hardcoded London profile through a separate POC WebView.~~ Fixed in #8:
  the report is measured through the production `WebViewConfigurator` over the active profile and
  device, and is gated on `DistributionCapabilities.developerDiagnostics`.
- Every `§n` reference in the code points at an **external spec that was never committed**.
  `docs/ARCHITECTURE_PLAN.md` is a response to it with independent numbering.
- Tinder still gates the browser. The viewport hypothesis was falsified — the fix shipped in
  `c8f9b55` and the gate persists. Next step needs `chrome://inspect` on a physical device.
  **Parked**, not blocking.

## Working rules

- Task tracking: **GitHub Issues**, canonical. One issue per work item; branch
  `feature/<issue>-<slug>`; PR closes it.
- No code changes in the main conversation — worker agents in isolated worktrees, merged via PR.
- Toolchain: JDK 17–21, `ANDROID_HOME` set. `./gradlew testDebugUnitTest` before any PR.
- Replacing a CI-built APK needs `adb uninstall` first (different debug keystore), which wipes
  saved profiles and the Keystore API key. See `docs/HANDOFF.md` §6.
