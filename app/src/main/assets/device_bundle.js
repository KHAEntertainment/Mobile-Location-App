// GeoAlign device-emulation bundle (spec §14). Injected at document-start, before page scripts, so
// JS-visible hardware signals line up with the emulated User-Agent. Screen/platform/touch overrides
// plus a navigator.userAgentData block (a Chromium shim, or hidden for Safari) built by the compiler.
(function () {
  try {
    var def = function (obj, prop, val) {
      try { Object.defineProperty(obj, prop, { get: function () { return val; }, configurable: true }); } catch (e) {}
    };

    def(navigator, "platform", "__NAV_PLATFORM__");
    def(navigator, "maxTouchPoints", __MAXTOUCH__);
    def(window, "devicePixelRatio", __DPR__);

    def(screen, "width", __SCREEN_W__);
    def(screen, "height", __SCREEN_H__);
    def(screen, "availWidth", __SCREEN_W__);
    def(screen, "availHeight", __SCREEN_H__);

    // navigator.userAgentData: Chromium shim with matching high-entropy values, or hidden for Safari.
    __UAD_BLOCK__
  } catch (e) {}
})();
