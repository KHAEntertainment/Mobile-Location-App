// GeoAlign device-emulation bundle (spec §14). Injected at document-start, before page scripts, so
// JS-visible signals line up with the emulated identity. The geometry block (screen/platform/touch)
// is present only for spoof presets; "This device" mode emits no geometry and keeps the real values.
// The userAgentData block is a Chromium shim, or hidden for Safari, built by the compiler.
(function () {
  try {
    var def = function (obj, prop, val) {
      try { Object.defineProperty(obj, prop, { get: function () { return val; }, configurable: true }); } catch (e) {}
    };

    __GEO_BLOCK__
    __UAD_BLOCK__
  } catch (e) {}
})();
