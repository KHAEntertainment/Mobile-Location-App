# POC Notes (Milestone 1)

What each proof-of-concept verified, kept as the record of Milestone 1.

**Historical.** The `poc.html` page and the harness that loaded it were deleted in issue #8: the
production settings matrix sets `allowFileAccess = false`, so a correctly configured browser cannot
load `file:///android_asset/` at all, and the harness proved nothing about it. What each POC checked
by hand is now checked by the diagnostics report, measured through the production
`WebViewConfigurator` (`ui/diagnostics/DiagnosticsScreen.kt`, `core/diagnostics/`).

## POC 1 — Document-start injection
The page checks `window.__GEOALIGN__`. If present, the environment bundle installed **before** page
JS ran. Reported coordinates should match the demo profile (London: 51.5074, -0.1278).

## POC 2 — Permission consistency
The app holds **no** Android location permission (assert via manifest). The native geolocation
prompt is force-denied by `BrowserPermissionPolicy`, yet the page still receives virtual
coordinates through the injected shim — proving the page cannot reach real GPS.

## POC 3 — Timezone / locale
`Intl.DateTimeFormat().resolvedOptions().timeZone` should read `Europe/London`;
`getTimezoneOffset` should reflect that zone; `navigator.language` should be `en-GB`.
Any API still showing the device's real zone is a documented limitation, not a silent pass.

## POC 4 — Browser identity
Reports UA, platform, viewport, touch points, and Client Hints. (Desktop-mode identity swapping
lands in Milestone 4; this build ships the mobile profile.)

## POC 5 — Local-network blocking (live)
`LocalNetworkInterceptor` applies `LocalNetworkPolicy` at `shouldInterceptRequest` for HTTP(S)
subresources. The page smoke-tests fetches to `127.0.0.1`, `192.168.0.1`, `10.0.0.1`, `[::1]`,
and the dword form `2130706433`. **Caveat:** because the page loads from `file://`, these fetches
are also subject to CORS/opaque-origin rules, so a green here is *indicative, not proof* — a
controlled `https://` page asserting on the `X-GeoAlign-Blocked` header gives the definitive
result (planned). Documented limits (not app-interceptable, reported honestly, not claimed
covered): **WebSocket** handshakes and **public-hostname-resolves-to-private** (DNS rebinding).
The WS row targets a routable private host — a real connection there reads as **fail**.

## POC 6 — Capture + WebRTC
`getUserMedia({audio,video})` must be **denied** (no camera/mic permission requested). WebRTC is
neutralized in-page (`RTCPeerConnection` forced to relay-only with empty ICE servers), so no
host/reflexive candidates are gathered. The ICE probe should report **0 candidates**, and any
candidate exposing a private/host address is a **fail**.

## POC 7 — Storage isolation (informational)
Sets a cookie + localStorage value so the eventual clear-session action can be verified to wipe
them. Full isolation findings (temp session vs persistent profile, restarts, service workers)
are gathered here before the production storage design is fixed.
