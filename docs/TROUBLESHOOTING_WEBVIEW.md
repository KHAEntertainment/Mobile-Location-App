# Diagnosing the embedded WebView

How to find out what a page actually sees inside GeoAlign, and what we already
know it sees. Written after a long misdiagnosis — the method in
[§3](#3-diagnosing-what-a-site-actually-checks) is the part worth keeping.

---

## 1. Solved: the zero-height CSS viewport

**Symptom.** Tinder showed *"Catch Us On The Flip Side — enjoy matching up from
a portrait view"*. Other sites laid out as though the window were a wide
desktop.

**Cause.** Compose's `AndroidView` leaves a child view's own `LayoutParams` at
`WRAP_CONTENT` and relies on Compose to measure it. **WebView derives its CSS
viewport height from its `LayoutParams`, not from the measured size**, so it
reported a viewport height of `0`:

```
(height: 0px)             true
100vh                     0px
(orientation: landscape)  true      <- a 368x0 viewport has an infinite aspect ratio
```

Every page was told it was in landscape — on every device preset, in both fold
states — while Chromium's own layout viewport was a correct `368x661`.

**Fix.** Explicit `MATCH_PARENT` `LayoutParams` on the WebView in
`BrowserScreen`'s `factory` (commit `58035e8`).

**Regression check.** In any page's console (see §3):

```js
matchMedia("(orientation: portrait)").matches   // must be true when the window is portrait
matchMedia("(height: 0px)").matches             // must be false, always
```

If a future layout change reintroduces a zero height, every orientation-aware
site breaks at once — that second line is the cheapest tripwire we have.

**Tinder's message was literally accurate.** It was reporting a genuinely
landscape viewport, not detecting an embedded WebView. Two plausible theories —
embedded-WebView fingerprint rejection, and an unhonoured `<meta viewport>` —
each survived for weeks because nobody measured `100vh`. Prefer a measurement
over a plausible story.

---

## 2. Native mode's identity

`DeviceProfiles.NATIVE` ("This device", the default) presents the identity of
the **device's own Chrome**, derived at runtime rather than hardcoded:

| Surface | Source |
|---|---|
| UA string | `NativeIdentity.reduceUserAgent` — Chrome's reduced form: frozen `Android 10; K`, `Chrome/MAJOR.0.0.0` |
| UA-CH + `Sec-CH-UA` headers | `NativeUaMetadata` via `WebSettingsCompat.setUserAgentMetadata` |
| screen / DPR / touch | untouched — the real hardware |

Two things to know before changing any of it:

- **Real Chrome sends a *reduced* UA.** It never reports the true OS version,
  model, or patch version. A UA carrying the real `Android 16; SM-F956U1` and a
  full `151.0.7922.169` is *more* identifying than real Chrome and matches no
  Chrome that currently exists. Stripping the `wv` marker is not sufficient.
- **The WebView's own client hints cannot be passed through** — they announce
  the brand `Android WebView` outright. `NativeUaMetadata` reuses the WebView's
  brand list as a base (so the GREASE entry and major versions track whatever
  WebView is installed) and renames only that one entry.

`DeviceBundleCompiler` deliberately emits **no** `userAgentData` JS shim for
native mode; `setUserAgentMetadata` is the single source, and it fixes the
request headers too. Adding a shim back would overwrite it and reintroduce a
UA-vs-UA-CH contradiction — which is what a site cross-checking the two reads
as active spoofing.

---

## 3. Diagnosing what a site actually checks

Debug builds enable WebView remote debugging. The **fastest** route is the
DevTools protocol over `adb` — scriptable, no GUI, and it works against the
device's own Chrome too, which is the reference you want to diff against.

```bash
# 1. find the app's devtools socket (matches the app PID)
adb shell cat /proc/net/unix | grep webview_devtools_remote

# 2. forward it
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>

# 3. list targets -> each page's webSocketDebuggerUrl
curl -s http://127.0.0.1:9222/json/list
```

Drive it with any WebSocket client (Node 22+ has `WebSocket` built in):
`Runtime.evaluate` to read signals, `Page.navigate` to load a URL,
`Page.getLayoutMetrics` to see what Chromium thinks the viewport is,
`Network.requestWillBeSentExtraInfo` to capture the headers actually sent.

**Diff against the device's own Chrome.** Chrome exposes
`localabstract:chrome_devtools_remote` the same way, so the same script can read
both and the difference *is* the answer. Two cautions:

- **Foreground the app you are probing.** A backgrounded WebView has its JS
  throttled and `Runtime.evaluate` will simply hang.
- **`navigator.userAgentData` needs a secure context** — `about:blank` returns
  nothing. Navigate to an `https://` page first.
- **`getHighEntropyValues` only returns the hints you ask for.** Request
  `uaFullVersion` and `fullVersionList` explicitly or they read as missing.

The GUI route still works: Chrome on a computer, `chrome://inspect`, pick the
GeoAlign WebView. Signals worth comparing against a real Chrome tab:

- `innerWidth`/`innerHeight`, `100vh`, `matchMedia("(orientation: portrait)")`
- `navigator.userAgent`, and `getHighEntropyValues` for platform / model / versions
- `screen.*`, `devicePixelRatio`, `navigator.maxTouchPoints`

---

## 4. Known remaining differences from real Chrome

Measured on a Galaxy Z Fold 6 (Android 16, WebView 151). None of these are
implicated in a current bug; they are what is left if a future site does gate on
embedded-WebView detection.

| Signal | Real Chrome | Our WebView |
|---|---|---|
| `window.chrome` | object (`loadTimes`, `csi`) | `undefined` |
| `Notification` | function | absent |
| `PushManager`, `PaymentRequest` | function | absent |
| `navigator.share`, `mediaSession`, `presentation` | present | absent |
| `navigator.bluetooth`, `usb`, `serial` | present | absent |

These are inherent WebView-vs-Chrome API differences, not configuration. Shimming
them is possible but widens the fingerprint-spoofing surface, so do it only
against a confirmed detection — measure first, per §1.
