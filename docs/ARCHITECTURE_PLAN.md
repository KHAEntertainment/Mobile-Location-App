# GeoAlign Browser — Initial Architecture Plan (Fable, Section 31 Response)

**Project:** Android Location-Aligned Privacy Browser (MVP)
**Role of this document:** The initial planning response required by spec §31, produced before broad implementation. It also records Milestone 0 research findings. Nothing here claims to be finished code.

**Status of gate:** Per agreement, this plan is delivered for review, and scaffolding + POC work proceeds in parallel unless you object to anything below.

---

## 0. Milestone 0 research findings (verified July 2026)

These are the current-fact checks that drive the choices in this plan.

**Android platform.** The current stable platform is **Android 16 (API 36)**. Google Play's target-API requirement moves to API 36 in 2026, so targeting 36 is the correct forward choice even though we are not shipping to Play. ([Android versions/API levels](https://apilevels.com/), [Play target-API requirement](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en))

**AndroidX WebKit.** Latest stable is **androidx.webkit:webkit:1.16.0** (May 2026). It carries the APIs this project depends on most:
- `WebViewCompat.addDocumentStartJavaScript(...)` — injects JS **before page scripts run**, into all frames, gated by the `DOCUMENT_START_SCRIPT` feature. This is the backbone of geolocation/timezone/locale virtualization.
- `UserAgentMetadata` with form-factor override (`Sec-CH-UA-Form-Factors`) — needed for a *consistent* desktop identity via User-Agent Client Hints, not just a UA string.
- `ServiceWorkerControllerCompat` / `WebViewClientCompat.shouldInterceptRequest` — the interception seam for local-network blocking, including in service workers.
- **webkit 1.16.0 raised its own minSdk from 21 to 24.** ([Webkit release notes](https://developer.android.com/jetpack/androidx/releases/webkit), [WebViewCompat reference](https://developer.android.com/reference/androidx/webkit/WebViewCompat))

**IP-geolocation provider — important change since the spec was written.** ipinfo.io has **dropped free city-level geolocation**. Their free *Lite* API now returns country/continent/ASN only — **no city, latitude, or longitude** — which this app fundamentally needs. City + lat/long now requires ipinfo's paid **Core** plan. ([IPinfo Lite API](https://ipinfo.io/developers/lite-api), [coverage of the change](https://botoi.com/blog/ipinfo-alternative-free-ip-geolocation/)) This changes the provider recommendation below (see §6) and is my first proposed spec adjustment.

**Sandbox/tooling reality.** This cloud session has JDK 21 and Gradle 8.14 available, but **`dl.google.com` and `maven.google.com` are network-blocked here**, so the Android SDK and AndroidX artifacts cannot be downloaded inside the sandbox, and **GitHub repo access is restricted to pre-authorized repos** (I could not create `geoalign-browser` from here). Implications for how we run this are in §14 — this is the point where desktop involvement becomes necessary.

---

## 1. Architectural overview

A **single-activity Jetpack Compose** app. All privacy-relevant behavior concentrates behind a small set of interfaces so it can be tested and independently validated (Opus) without a live device:

- **Readiness pipeline** (pure/testable): VPN transport → internet reachability → effective public IP → IP geolocation → selected browser profile. A reducer folds these into one `ReadinessState`. No step is allowed to imply protection it hasn't verified.
- **Browser environment** (the hard part): a `BrowserEnvironment` value object (coordinates, timezone, locale, UA/desktop identity) is compiled into a **document-start JavaScript bundle** injected before any page script runs, plus native-side WebSettings (UA, UA-CH metadata, mixed-content, file access).
- **Policy layer**: `BrowserPermissionPolicy` (one centralized decision point for geolocation/camera/mic/sensors/etc.), `NavigationPolicy` (internal-origin protection, external-intent validation, HTTP warning flow), and `LocalNetworkPolicy` (CIDR/hostname blocking at `shouldInterceptRequest`).
- **Diagnostics**: an app-owned page served from a locked internal origin that measures what the browser *actually* exposes, and grades Pass / Warning / Unsupported / Failed — never a fake "100% anonymous" score.

Design principle throughout: **treat all web content as hostile, and never claim a protection the installed WebView can't actually deliver.** Where a control is best-effort, the UI and diagnostics say so.

---

## 2. Data & network flow

```
                 ┌────────────────────────── App process ──────────────────────────┐
                 │                                                                   │
 User ──opens──► │  Onboarding ─► Readiness Dashboard                                │
                 │                     │                                             │
                 │      ┌──────────────┼───────────────────────────┐                │
                 │      ▼              ▼                            ▼                │
                 │  VpnStatusRepo  EffectiveIpRepo ──HTTPS──► IP-check providers     │──► ipify / icanhazip (IP only)
                 │  (ConnectivityMgr)     │                                          │
                 │      │                 └──► IpGeolocationProvider ──HTTPS──►       │──► geo provider (IP → city/tz)
                 │      │                              │                             │
                 │      ▼                              ▼                             │
                 │  ReadinessReducer ◄──────── LocationProfile (suggested/edited)    │
                 │                                     │                             │
                 │                                     ▼                             │
                 │                       BrowserEnvironment (coords, tz, locale, UA) │
                 │                                     │                             │
                 │                        compiled to document-start JS + WebSettings│
                 │                                     ▼                             │
                 │   WebView ◄── injected BEFORE page JS ──►  loaded web page ───────┼──► the site the user visits
                 │      ▲                                                            │
                 │      │  shouldInterceptRequest → LocalNetworkPolicy (block priv.) │
                 │      └── BrowserPermissionPolicy (geo=virtual/deny, cam/mic=deny) │
                 └───────────────────────────────────────────────────────────────────┘

Real Android GPS  ✗ never requested, never reaches WebView.
Only outbound app-owned calls: 2 IP-check endpoints + 1 geo endpoint, all HTTPS, bounded, unlogged in release.
```

---

## 3. Module structure

Spec §24 lists ~19 Gradle modules. For an MVP that's more build overhead than boundary value, so I propose **collapsing to 6 Gradle modules while preserving the logical package boundaries** (this is proposed spec change #2):

```
:app                     single activity, Compose nav, DI wiring, diagnostics host
:core                    model + security utils + WebView feature compat + UI theme
                         (packages: core.model, core.security, core.net, core.ui)
:data                    settings, profiles (Room), geolocation clients, IP/VPN repos
:web                     BrowserEnvironment, injector, permission/navigation/localnet policy,
                         internal diagnostics assets
:feature                 onboarding, readiness, profiles, browser, diagnostics, settings screens
:testfixtures            shared fakes + the controlled web test pages
```

Every important abstraction from §24 remains a named interface: `VpnStatusRepository`, `EffectiveIpRepository`, `IpGeolocationProvider`, `LocationProfileRepository`, `BrowserEnvironment`, `BrowserEnvironmentInjector`, `BrowserPermissionPolicy`, `NavigationPolicy`, `LocalNetworkPolicy`, `BrowserSessionManager`, `WebViewFeatureCompatibility`, `DiagnosticsRepository`. If you'd rather I honor the full module split, say so — it's a config choice, not a rework.

---

## 4. Min / Target SDK

- **minSdk = 26 (Android 8.0).** Driven by webkit 1.16.0's own minSdk of 24, pushed to 26 because document-start injection and UA-CH override are only *reliable* on reasonably modern System WebView builds, and 26 removes a class of legacy WebView quirks. The spec suggested "Android 10+ unless justified"; 26 is one notch lower and I can move it to 29 if you prefer strict adherence — flagging as proposed change #3.
- **targetSdk / compileSdk = 36 (Android 16).**

Because the *installed System WebView version* matters more than the OS for our features, `WebViewFeatureCompatibility` checks `WebViewFeature.isFeatureSupported(...)` at runtime and degrades gracefully rather than assuming behavior from the OS level.

---

## 5. Required WebView / AndroidX WebKit features

Runtime-gated via `WebViewFeature`:
- `DOCUMENT_START_SCRIPT` — pre-page-script injection (geo/tz/locale). **Hard dependency**; if absent we show a warning and do not claim location isolation.
- `USER_AGENT_METADATA` — desktop UA-CH consistency.
- `SERVICE_WORKER_BASIC_USAGE` + `SERVICE_WORKER_CONTENT_ACCESS` — extend request policy into service workers.
- `SAFE_BROWSING_ENABLE`, `FORCE_DARK`(cosmetic only), `WEB_MESSAGE_LISTENER` (only if a tightly-scoped, token-guarded bridge proves necessary).
Native WebSettings: `mixedContentMode = NEVER_ALLOW`, `allowFileAccess=false`, `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false`, JS enabled only for loaded content, Safe Browsing on.

---

## 6. IP-check and geolocation services

**IP-check (effective public IP), 2 providers for resilience, HTTPS, bounded, keyless:**
- Primary: `https://api.ipify.org?format=json`
- Secondary: `https://icanhazip.com` (plain-text, tiny) or Cloudflare `https://one.one.one.one/cdn-cgi/trace`
Both return IP only; we detect IPv4-vs-IPv6 divergence by querying v4- and v6-forced hosts where the network supports it.

**IP → geolocation (city/lat/long/timezone/ASN). Given the ipinfo change, revised recommendation:**
- **Default: ipwho.is or ipapi.co** — keyless, HTTPS, returns city + lat/long + IANA timezone + ASN, no key to manage for first-run. Good enough for "approximate VPN exit."
- **Optional upgrade: ipinfo.io Core** (paid, cheap) for better VPN/hosting classification — user pastes an API key stored via Android Keystore, exactly as the spec's key-handling requires.
The `IpGeolocationProvider` interface makes this a one-class swap; diagnostics show which provider produced the estimate. **This supersedes the earlier ipinfo-as-default plan** because ipinfo no longer returns free city/lat-long.

All geo/IP calls: HTTPS-only, strict connect/read timeouts, capped response size, no analytics SDKs, no request history, full IP redacted from release logs and shown only on the diagnostics screen.

---

## 7. Exact expected Android permissions

Merged-manifest target (audited, not just declared):
- `android.permission.INTERNET` — required.
- `android.permission.ACCESS_NETWORK_STATE` — required (VPN transport detection).
- `android.permission.POST_NOTIFICATIONS` — **omitted in MVP** unless a concrete notification feature lands; not needed for core flow.

Also present in the merged manifest, and expected: `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission scoped to this app's own package that `androidx.core` merges in so it can register unexported runtime receivers. It grants nothing to any other app.

Explicitly absent: all location, wifi-state, bluetooth/nearby, activity-recognition, camera, microphone, contacts, phone-state, broad storage, and local-network permissions. Any transitive permission a library tries to merge in gets `tools:node="remove"`.

**`app/src/main/AndroidManifest.xml` is the authoritative list of exactly which permission names each of those categories covers.** This section names the categories; the manifest names the permissions, groups them under the same category headings, and carries a comment for each category deliberately *not* removed (`POST_NOTIFICATIONS`, the scoped `READ_MEDIA_*` trio). Deliberately not restated here, so the two cannot drift apart — which is what happened before: this list named ten categories while the manifest covered four.

**Not yet asserted by a test.** An earlier draft of this section claimed an instrumentation test reads the merged manifest and asserts these absent. No such test exists, and there is no `app/src/androidTest` source set at all — the guarantee currently rests on the manifest alone. That assertion is M7 release-gate work; when it lands it must allow the two required permissions plus the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` noted above.

---

## 8. Document-start script injection approach

`WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginRules)` registered at WebView creation, before the first `loadUrl`, so it runs in **every frame before page-authored JS**. The script installs the virtual environment (geolocation shims, timezone/locale alignment) into an **isolated context** and freezes the surface where feasible. Compatibility gate: if `DOCUMENT_START_SCRIPT` is unsupported, we surface a warning, disable the "location isolated" claim, and offer to open System WebView update settings. POC 1 validates ordering across inline/head/deferred/async/iframe/popup/reload/back-forward/service-worker cases before we trust it.

---

## 9. Local-network blocking approach

A `LocalNetworkPolicy` invoked from `shouldInterceptRequest` (main frame, subresources, and service-worker requests where `SERVICE_WORKER_CONTENT_ACCESS` is supported). It:
- Parses the destination host; classifies literal IPv4/IPv6 against blocked CIDRs (§16 list: 127/8, 10/8, 100.64/10, 169.254/16, 172.16/12, 192.168/16, 224/4, plus IPv6 loopback/link-local/ULA/multicast/mapped-private).
- Blocks/scrutinizes `localhost`, `.local`, mDNS, embedded credentials, and alternative numeric IP notations (octal/hex/dword/short forms) by normalizing before comparison.
- Guards against DNS-rebinding and public→private redirects by re-checking each hop of a redirect chain and, where resolution is visible to us, the resolved address.
**Honest limitation:** some Chromium network paths (certain WebSocket and pre-resolved connections) are not fully interceptable from an app-level WebView. We do **not** add a VPN service. POC 5 measures exactly what gets through and diagnostics/docs report the real coverage rather than claiming total isolation.

---

## 10. Desktop User-Agent Client Hints approach

Desktop mode is more than a UA string: we set a current, plausible **desktop Chromium on Windows/Linux** identity and align it across (a) the UA string, (b) `UserAgentMetadata` (platform, architecture, model, `mobile=false`, **form factor = Desktop**), and (c) viewport/initial-scale/zoom/touch-disclosure via WebSettings and injected hints. Chromium **major version is synchronized to the installed WebView** to avoid contradictions. We deliberately do **not** advertise Safari. POC 4 checks consistency across request UA, JS UA, Client Hints, mobile flag, viewport, touch points, and platform on real detection pages.

---

## 11. Expected limitations of timezone virtualization

WebView has no per-instance OS timezone. We align at the JS layer: `Intl.DateTimeFormat().resolvedOptions().timeZone`, explicit `Intl.DateTimeFormat` calls, `Date.prototype.getTimezoneOffset`, and local date rendering, using the profile's IANA zone. **Honest limitations:** deep `Date` internals and some `Intl` edge paths can leak the underlying device offset; historical DST transitions computed from a full tz database are hard to reproduce perfectly in-page. We prefer deterministic consistency over fragile monkey-patching, enumerate every inconsistent API found in POC 3 (tested against America/Los_Angeles, America/New_York, Europe/London, Asia/Tokyo), and the diagnostics page shows any unmodified path rather than the app claiming complete virtualization.

---

## 12. Expected limitations of WebView storage isolation

Android WebView shares storage **globally within the app process** by default. We can improve isolation with data-directory suffixes and disciplined clearing, but true simultaneous multi-profile isolation (separate cookie jars per profile, concurrently) is not reliably achievable app-side without multi-process complexity that's out of MVP scope. **Plan:** support (a) a **Temporary Session** that wipes cookies/cache/DOM storage/service workers/history on end, and (b) **one active Persistent Profile at a time**, with an explicit warning when switching location on an existing persistent profile. We will **not** claim perfect per-profile isolation. POC 7 determines exactly what clearing/partitioning holds across tabs, restarts, service workers, and cache.

---

## 13. The seven POC plans

Each POC is an isolated, buildable harness with a controlled test page and a pass/fail readout; Fable reviews results before approving production architecture (§23).

1. **Document-start location injection** — prove the virtual geo is present before page JS in inline/external/deferred/async scripts, same- & cross-origin iframes, dynamic iframes, popups, reload, back-forward, and a service-worker-controlled page.
2. **Permission consistency** — geolocation reports the expected virtual permission state; WebView never requests/receives Android location; no fallback to real GPS.
3. **Timezone virtualization** — exercise Date/Intl across the 4 zones; document every inconsistent API.
4. **Desktop identity** — verify request UA, JS UA, Client Hints, mobile flag, viewport, touch, platform all agree on detection pages.
5. **Local-network blocking** — controlled endpoints for direct private IPv4/IPv6, public→private redirect, public host resolving to private, alt IP notation, WebSocket-to-local, iframe-to-router.
6. **WebRTC leakage** — confirm default config leaks no local addresses and no non-VPN public address; enumerate ICE candidates.
7. **WebView storage isolation** — measure achievable isolation for temp sessions, persistent profiles, tabs, restarts, service workers, cache, cookies, DOM storage.

**POCs 1–4 can be delivered first as a single sideloadable "GeoAlign POC" APK** you run on your phone — that's the fastest way to get real signal from mobile.

---

## 14. Milestone & delegation plan — and where desktop comes in

Delegation model per spec: **Fable** plans/sequences/reconciles; **Sonnet** implements in small reviewable increments with tests; **Opus** adversarially validates and classifies findings (Critical→Informational); a milestone isn't done until Critical/High are resolved or documented as accepted limitations. In this session I (Fable) drive, spawn Sonnet-class implementer subagents for code increments, and spawn an Opus-class validator subagent for the adversarial passes.

**The honest constraint:** this cloud sandbox **cannot download the Android SDK or AndroidX (Google's Maven is blocked here) and cannot run an Android emulator**. So I can author the entire project, all logic, all tests, docs, and the JS environment bundle here and keep it in version control — but the **APK build, instrumentation tests, and on-device validation happen on your desktop** (Android Studio) or an unblocked CI. I'll make that a one-command experience.

- **M0 Research & Architecture** — ✅ this document.
- **M1 POCs 1–7** — I author the harness code + controlled test pages here; **you build/run the POC APK on desktop + phone** and send results back. Highest-risk items first.
- **M2 Foundation** — project skeleton, Room persistence, settings, networking, VPN/IP/geo repos (all unit-testable here).
- **M3 Browser core** — WebView lifecycle, nav, tabs, security settings, permission policy, internal origins.
- **M4 Environment alignment** — geo/timezone/locale/desktop + compat detection.
- **M5 Leak protection** — local-network policy, WebRTC policy, sensor lockdown, diagnostics tests.
- **M6 UX & reliability** — onboarding, readiness, profiles, VPN-loss handling, crash recovery, data clearing.
- **M7 Validation & release** — full suite, Opus adversarial review, fix findings, signed sideloadable APK, release checklist.

**What I can complete entirely from mobile/cloud:** all Kotlin logic and unit tests, the injected JS bundle and controlled test pages, architecture/threat/privacy docs, dependency+license inventory, and version control. **What needs your desktop:** first Gradle sync that pulls AndroidX, APK builds, instrumentation/emulator tests, and real-device validation across the §27 matrix. I'll tell you exactly when we hit that line — which is at the moment we first want a runnable APK (end of M1).

---

## 15. Proposed changes to the specification (with rationale)

1. **Default geo provider is no longer ipinfo free** — ipinfo dropped free city/lat-long (verified). Default to a keyless HTTPS provider (ipwho.is/ipapi.co) with ipinfo **Core** as an optional keyed upgrade. Keeps the "at least one API-backed provider, easily replaceable" requirement intact.
2. **Collapse ~19 Gradle modules to 6** while preserving logical boundaries and all named abstractions — the spec explicitly permits reducing module count for MVP.
3. **minSdk = 26** rather than the suggested 29/"Android 10+" — justified by webkit 1.16.0's minSdk of 24 and reliability of our required features; movable to 29 on request.
4. **Process note (not a spec change):** APK build + on-device tests run on your desktop/CI because Google's Maven and the Android SDK are unreachable from this sandbox. No change to requirements, only to *where* the compile/validate steps execute.

Everything else in the spec is accepted as written, including all the "do not claim more than you can prove" honesty constraints, the omitted-permission posture, and the quality gates in §29.

---

*Prepared by Fable. Awaiting your nod on the three substantive proposed changes (provider default, module count, minSdk). I'm proceeding with scaffolding + POC 1–4 authoring in the meantime; none of it locks in those three decisions irreversibly.*
