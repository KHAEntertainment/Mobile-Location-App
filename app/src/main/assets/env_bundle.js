/*
 * GeoAlign browser environment bundle.
 * Injected via WebViewCompat.addDocumentStartJavaScript BEFORE any page script runs,
 * into every frame. Placeholders __LAT__/__LNG__/__ACC__/__TZ__/__LANG__/__LANGS__ are
 * substituted natively at injection time. Runs in an isolated context where supported.
 *
 * Scope note: this is best-effort browser-level virtualization, NOT OS-level. The diagnostics
 * page reports any path that remains unmodified; the app does not claim complete virtualization.
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
  } catch (e) { /* leave native geolocation absent rather than throw */ }

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
            var m = /GMT([+-]\d{1,2})(?::?(\d{2}))?/.exec(parts[i].value);
            if (m) {
              var h = parseInt(m[1], 10);
              var mm = m[2] ? parseInt(m[2], 10) : 0;
              var sign = h < 0 ? 1 : -1; // JS offset is inverted vs GMT sign
              return sign * (Math.abs(h) * 60 + mm);
            }
          }
        }
      } catch (e) {}
      return _getOffset.call(this);
    };
  } catch (e) {}

  // Marker so the diagnostics page can confirm the bundle installed at document-start.
  try {
    Object.defineProperty(window, "__GEOALIGN__", {
      value: { installed: true, tz: TZ, lang: PRIMARY_LANG, lat: LAT, lng: LNG },
      configurable: false, enumerable: false, writable: false,
    });
  } catch (e) {}
})();
