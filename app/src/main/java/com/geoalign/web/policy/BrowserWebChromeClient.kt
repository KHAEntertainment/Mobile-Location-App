package com.geoalign.web.policy

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Production browser WebChromeClient: denies all capture permissions and the native geolocation
 * prompt (spec §17–18), and reports load progress + page title for the toolbar (spec §10).
 */
class BrowserWebChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String?) -> Unit,
) : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        onTitle(title)
    }
}
