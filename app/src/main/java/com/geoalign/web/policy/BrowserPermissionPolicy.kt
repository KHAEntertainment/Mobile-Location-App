package com.geoalign.web.policy

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient

/**
 * POC 6 / §17–18 — a single, centralized permission decision point instead of scattered
 * WebView callbacks. MVP defaults deny every sensitive browser capability:
 *
 *  - Camera / microphone / audio / video capture  → denied (prevents WebRTC media + getUserMedia)
 *  - Native geolocation prompt                     → always denied; location is supplied by the
 *                                                     injected JS environment, never the OS. The app
 *                                                     holds no Android location permission, so even a
 *                                                     mistaken grant here cannot reach real GPS.
 *
 * Ordinary <video>/<audio> playback is unaffected — that does not go through PermissionRequest.
 */
class BrowserPermissionPolicy : WebChromeClient() {

    /** Deny all capture-oriented WebView permission requests outright. */
    override fun onPermissionRequest(request: PermissionRequest) {
        // Denying (not granting an empty set) is the explicit, auditable choice.
        request.deny()
    }

    /**
     * Never hand a page the real device location. We return allow=false, retain=false; the page
     * still receives virtual coordinates through the injected navigator.geolocation shim, which
     * runs entirely in-page and does not consult this native path.
     */
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, /* allow = */ false, /* retain = */ false)
    }
}
