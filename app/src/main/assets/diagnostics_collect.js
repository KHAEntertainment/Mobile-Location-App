/*
 * GeoAlign diagnostics collector.
 *
 * Runs as an ORDINARY page script, after the document-start bundles have already been installed by
 * WebViewConfigurator — exactly where a hostile site's own scripts run. That ordering is the whole
 * point: everything it reads is what a real page sees in the real browser configuration, not what
 * the app believes it configured.
 *
 * It publishes a JSON string on window.__geoalignDiagnostics once the (asynchronous) geolocation
 * answer has arrived or timed out; the Android side polls for that value. Nothing here is injected
 * into browsing sessions — it is evaluated only on the diagnostics document.
 */
(function () {
  "use strict";

  if (window.__geoalignDiagnosticsStarted) return;
  window.__geoalignDiagnosticsStarted = true;
  window.__geoalignDiagnostics = null;

  var out = { languages: [] };

  function num(v) {
    return typeof v === "number" && isFinite(v) ? v : null;
  }
  function whole(v) {
    return typeof v === "number" && isFinite(v) ? Math.round(v) : null;
  }
  function finish() {
    if (window.__geoalignDiagnostics) return;
    try {
      window.__geoalignDiagnostics = JSON.stringify(out);
    } catch (e) {
      window.__geoalignDiagnostics = '{"error":"the environment could not be serialised"}';
    }
  }

  try {
    var geo = navigator.geolocation;
    // The one direct piece of evidence that the document-start script ran: a shimmed function does
    // not stringify to "[native code]".
    out.geolocationShimmed = !!geo && String(geo.getCurrentPosition).indexOf("[native code]") < 0;

    try {
      out.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || null;
    } catch (e) {
      out.timezone = null;
    }
    out.timezoneOffsetMinutes = whole(new Date().getTimezoneOffset());
    out.language = navigator.language || null;
    out.languages = Array.prototype.slice.call(navigator.languages || []);
    out.userAgent = navigator.userAgent || null;
    out.platform = navigator.platform || null;

    var uad = navigator.userAgentData;
    out.userAgentDataPresent = !!uad;
    if (uad) {
      out.userAgentDataPlatform = uad.platform || null;
      out.userAgentDataMobile = !!uad.mobile;
    }

    out.screenWidth = whole(screen.width);
    out.screenHeight = whole(screen.height);
    out.devicePixelRatio = num(window.devicePixelRatio);
    out.maxTouchPoints = whole(navigator.maxTouchPoints);
  } catch (e) {
    out.error = String(e);
  }

  // A position that never arrives must still produce a report — an unanswered getCurrentPosition is
  // itself the finding.
  setTimeout(function () {
    if (!out.geolocationError && out.latitude === undefined) {
      out.geolocationError = "no position within 2.5s";
    }
    finish();
  }, 2500);

  try {
    if (navigator.geolocation && navigator.geolocation.getCurrentPosition) {
      navigator.geolocation.getCurrentPosition(
        function (pos) {
          try {
            out.latitude = num(pos.coords.latitude);
            out.longitude = num(pos.coords.longitude);
            out.accuracy = num(pos.coords.accuracy);
          } catch (e) {
            out.geolocationError = String(e);
          }
          finish();
        },
        function (err) {
          out.geolocationError = err && err.message ? err.message : "position unavailable";
          finish();
        },
        { timeout: 2000, maximumAge: 0 }
      );
    } else {
      out.geolocationError = "navigator.geolocation is unavailable";
      finish();
    }
  } catch (e) {
    out.geolocationError = String(e);
    finish();
  }
})();
