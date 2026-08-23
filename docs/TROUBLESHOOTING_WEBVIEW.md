# WebView-hostile sites (e.g. Tinder)

Some sites detect embedded WebViews or inconsistent device fingerprints and
refuse to render their normal experience. Tinder shows a **"Catch Us On The
Flip Side — enjoy matching up from a portrait view"** screen.

## What we do about it

1. **"This device" mode (default).** Instead of spoofing a fake device, the
   browser presents the real hardware geometry with a cleaned Chrome
   User-Agent (the `; wv` embedded-WebView marker and `Version/4.0` /
   `Build/…` tokens are stripped). Device menu → *This device (recommended)*.

2. **Honor the viewport meta.** `useWideViewPort = true` +
   `loadWithOverviewMode = true` make the WebView respect
   `<meta name="viewport" content="width=device-width">` the way mobile Chrome
   does. Without it, responsive sites lay out at the raw view width, so
   `window.innerWidth/innerHeight` and the `(orientation: landscape)` media
   query can read as a wide desktop — which triggers Tinder's portrait gate.

## Diagnosing what a site actually checks

Debug builds enable WebView remote debugging. On a computer running Chrome:

1. Connect the phone by USB (USB debugging on).
2. Open `chrome://inspect` → find the GeoAlign WebView → **inspect**.
3. In the console, compare against a real Chrome tab on the same site:
   - `navigator.userAgent`
   - `navigator.userAgentData` and `await navigator.userAgentData.getHighEntropyValues(["platform","platformVersion","model","mobile"])`
   - `innerWidth`, `innerHeight`, `screen.width`, `screen.height`, `devicePixelRatio`
   - `matchMedia("(orientation: portrait)").matches`
   - `navigator.maxTouchPoints`

Whatever differs from real Chrome is the lever to pull next.

## Known remaining gap

The `Sec-CH-UA` **request headers** still carry the WebView identity even in
"This device" mode (client-side JS is corrected, HTTP headers are not). Closing
that needs `WebSettingsCompat.setUserAgentMetadata`. Only worth doing if a site
is confirmed (via the inspector above) to gate on the headers rather than JS.
