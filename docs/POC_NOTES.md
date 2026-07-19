# POC Notes (Milestone 1)

What each proof-of-concept verifies and how to read the on-device diagnostics page
(`file:///android_asset/poc.html`, loaded automatically by the POC harness).

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
`LocalNetworkInterceptor` applies `LocalNetworkPolicy` at `shouldInterceptRequest`. The page
actively attempts fetches to `127.0.0.1`, `192.168.0.1`, `10.0.0.1`, `[::1]`, the dword form
`2130706433`, and a `ws://127.0.0.1` socket. **"blocked" = pass; "REACHED"/"OPEN" = fail.**
Known gap: some pre-resolved / WebSocket paths and public-hostname-resolves-to-private are not
fully interceptable app-side; those are called out rather than claimed as covered.

## POC 6 — Capture + WebRTC
`getUserMedia({audio,video})` must be **denied** (no camera/mic permission requested).
ICE candidate gathering is inspected for any private/host address — zero leaky candidates = pass.

## POC 7 — Storage isolation (informational)
Sets a cookie + localStorage value so the eventual clear-session action can be verified to wipe
them. Full isolation findings (temp session vs persistent profile, restarts, service workers)
are gathered here before the production storage design is fixed.
