/*
 * GeoAlign browser environment bundle.
 * Injected via WebViewCompat.addDocumentStartJavaScript BEFORE any page script runs,
 * into every frame. Placeholders __LAT__/__LNG__/__ACC__/__TZ__/__LANG__/__LANGS__ are
 * substituted natively at injection time.
 *
 * IMPORTANT (validated limitation): document-start scripts run in the page's MAIN JS world,
 * not an isolated content-script world. These overrides are therefore best-effort and
 * detectable, and a determined page may reach a native path (e.g. a freshly created child
 * frame's clean navigator). Real-device-location safety does NOT rely on these shims: the app
 * holds no Android location permission and BrowserPermissionPolicy denies the native geolocation
 * prompt, so every bypass lands on permission-denied, never real GPS.
 */
(function () {
  "use strict";

  var LAT = __LAT__;
  var LNG = __LNG__;
  var ACC = __ACC__;
  var TZ = "__TZ__";
  var PRIMARY_LANG = "__LANG__";
  var LANGS = __LANGS__; // JSON array

  // ---- Geolocation virtualization (spec §11) ----
  try {
    function makePosition() {
      return {
        coords: {
          latitude: LAT,
          longitude: LNG,
          accuracy: ACC,
          altitude: null,
          altitudeAccuracy: null,
          heading: null,
          speed: null,
        },
        timestamp: Date.now(),
      };
    }
    var watchers = {};
    var nextId = 1;
    var virtualGeo = {
      getCurrentPosition: function (success) {
        if (typeof success === "function") {
          setTimeout(function () { success(makePosition()); }, 0);
        }
      },
      watchPosition: function (success) {
        var id = nextId++;
        if (typeof success === "function") {
          watchers[id] = setInterval(function () { success(makePosition()); }, 1000);
          setTimeout(function () { success(makePosition()); }, 0);
        }
        return id;
      },
      clearWatch: function (id) {
        if (watchers[id]) { clearInterval(watchers[id]); delete watchers[id]; }
      },
    };
    Object.defineProperty(navigator, "geolocation", {
      value: virtualGeo, configurable: false, enumerable: true, writable: false,
    });
    // Also shadow the prototype getter so `Navigator.prototype.geolocation` cannot be used to
    // reach the native object. Best-effort; ignored if the platform disallows it.
    try {
      Object.defineProperty(Navigator.prototype, "geolocation", {
        get: function () { return virtualGeo; }, configurable: true, enumerable: true,
      });
    } catch (e2) {}
  } catch (e) {
    // If defineProperty throws, native navigator.geolocation remains PRESENT but un-shimmed.
    // Real GPS is still unreachable because the native permission prompt is denied app-side.
  }

  // ---- Language / locale alignment (spec §13) ----
  try {
    Object.defineProperty(navigator, "language", { get: function () { return PRIMARY_LANG; }, configurable: true });
    Object.defineProperty(navigator, "languages", { get: function () { return LANGS.slice(); }, configurable: true });
  } catch (e) {}

  // ---- Timezone alignment (spec §12), best-effort ----
  try {
    var _DTF = Intl.DateTimeFormat;
    function wrapDTF(locales, options) {
      options = options || {};
      if (options.timeZone === undefined) options.timeZone = TZ;
      return new _DTF(locales || PRIMARY_LANG, options);
    }
    wrapDTF.prototype = _DTF.prototype;
    wrapDTF.supportedLocalesOf = _DTF.supportedLocalesOf;
    Intl.DateTimeFormat = wrapDTF;

    var _resolved = Intl.DateTimeFormat().resolvedOptions();
    // getTimezoneOffset override: compute offset for TZ using the formatter.
    var _getOffset = Date.prototype.getTimezoneOffset;
    Date.prototype.getTimezoneOffset = function () {
      try {
        var dtf = new _DTF("en-US", { timeZone: TZ, timeZoneName: "shortOffset" });
        var parts = dtf.formatToParts(this);
        for (var i = 0; i < parts.length; i++) {
          if (parts[i].type === "timeZoneName") {
            var val = parts[i].value;
            // A bare "GMT"/"UTC" (no signed digits) means offset 0 — e.g. Europe/London in winter.
            // Without this, the regex misses and we'd leak the DEVICE's real offset.
            if (/^(GMT|UTC)$/.test(val)) return 0;
            var m = /(?:GMT|UTC)([+-]\d{1,2})(?::?(\d{2}))?/.exec(val);
            if (m) {
              var h = parseInt(m[1], 10);
              var mm = m[2] ? parseInt(m[2], 10) : 0;
              var sign = h < 0 ? 1 : -1; // JS offset is inverted vs GMT sign
              return sign * (Math.abs(h) * 60 + mm);
            }
          }
        }
      } catch (e) {}
      // Fall back to 0 rather than the real device offset, to avoid a real-offset leak.
      return 0;
    };
  } catch (e) {}

  // ---- WebRTC leak neutralization (spec §17) ----
  // WebRTC is disabled-by-default in the MVP. We can't set iceTransportPolicy from native, so we
  // neutralize in-page: force relay-only and strip ICE servers, so no host/server-reflexive
  // candidates (which would expose local or non-VPN addresses) are ever gathered. Data/media are
  // effectively disabled; ordinary <video>/<audio> playback is unaffected (not WebRTC).
  try {
    var NativeRTC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (NativeRTC) {
      var GuardedRTC = function (config, constraints) {
        config = config || {};
        config.iceServers = [];               // no STUN/TURN → no reflexive/relay candidates
        config.iceTransportPolicy = "relay";  // suppress host candidates
        var pc = new NativeRTC(config, constraints);
        return pc;
      };
      GuardedRTC.prototype = NativeRTC.prototype;
      window.RTCPeerConnection = GuardedRTC;
      if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = GuardedRTC;
    }
  } catch (e) {}

  // Diagnostics marker: presence only, no profile data (avoids re-exposing coords / narrowing
  // fingerprint). The diagnostics page uses navigator.geolocation for the actual values.
  try {
    Object.defineProperty(window, "__GEOALIGN__", {
      value: { installed: true },
      configurable: false, enumerable: false, writable: false,
    });
  } catch (e) {}
})();
