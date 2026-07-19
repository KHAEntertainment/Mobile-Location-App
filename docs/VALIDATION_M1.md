# Milestone 1 Validation — Adversarial Review & Dispositions

An independent adversarial pass (the "Opus" validator role, spec §2) attempted to disprove the
POC layer's security/privacy claims. Verdict: the **no-real-GPS guarantee holds** (defense in
depth — no location permission + native prompt denied), and the literal-IP blocker is solid.
Nearby-network protection was only **partial**. Findings and what we did about each:

| # | Sev | Finding | Disposition |
|---|-----|---------|-------------|
| 1 | HIGH | WebSocket bypasses the interceptor (not called for `ws://` handshakes) | **Accepted limitation.** Not interceptable from an app-level WebView without a VPNService (out of MVP scope). Claim narrowed to "HTTP(S) subresources by literal host." POC probe corrected to target a routable private host and report a real connection as FAIL. |
| 2 | HIGH | WebRTC ICE unmitigated → possible local/non-VPN address leak | **Fixed.** `env_bundle.js` wraps `RTCPeerConnection` to force `iceTransportPolicy:'relay'` and empty `iceServers`, so no host/reflexive candidates are gathered. Diagnostics flag any private candidate as fail. |
| 3 | MED | `getTimezoneOffset` leaks the real device offset for GMT/UTC (e.g. London in winter) | **Fixed.** Bare `GMT`/`UTC` now returns 0; fallback is 0, never the device offset. |
| 4 | MED | POC 5 diagnostics run from `file://` → cannot prove the interceptor blocked | **Documented.** Section relabeled a smoke test; a controlled `https://` page asserting on `X-GeoAlign-Blocked` is planned. The 403 itself is a genuine block; only the test was confounded. |
| 5 | MED | "Isolated context / freezes surface" claim is false (main-world injection) | **Fixed (docs).** Corrected in `env_bundle.js` header and ARCHITECTURE_PLAN §8. Added a prototype-getter shadow as best-effort hardening. |
| 6 | MED | `window.__GEOALIGN__` re-exposed the profile + fingerprints the tool | **Fixed.** Marker reduced to `{installed:true}`; no coords/tz/lang. |
| 7 | LOW | IPv4-compatible `::a.b.c.d` not reclassified (comment claimed it was) | **Fixed.** Now reclassifies embedded v4 for `::a.b.c.d`; tests added. |
| 8 | LOW | DNS-rebinding: only `request.url.host` classified, not resolved IP | **Accepted for M1, tracked for M2.** The resolved-address re-check is not yet implemented; must not be assumed to exist. |
| 9 | LOW | `parseUintAnyRadix` accepted negatives; `192.88.99.0/24` (6to4) missing | **Fixed.** Signed input rejected; 6to4 anycast added; tests added. |
| 10 | INFO | `mixedContentMode` not set explicitly | **Fixed.** Set `MIXED_CONTENT_NEVER_ALLOW`. |
| 11 | INFO | `allowFileAccess=false` + `file://` asset load looked contradictory | **Confirmed fine.** `android_asset` remains loadable; that flag governs arbitrary filesystem access only. No change. |
| 12 | INFO | "leave native geolocation absent" comment was inaccurate | **Fixed (comment).** |

No Critical findings. The two HIGH items are architectural limits of an app-level WebView; #2
is now mitigated in-page, #1 is honestly scoped rather than falsely claimed. Cleared to proceed
to Milestone 2, with the DNS-rebinding resolved-address re-check carried as an explicit M2 task.
