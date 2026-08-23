package com.geoalign.web.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.geoalign.core.browser.DownloadEnqueuer
import com.geoalign.core.browser.DownloadRequest

/**
 * Routes an accepted download to Android's `DownloadManager`, into the public Downloads directory
 * (spec §19). The browser deliberately does not fetch the bytes itself: the system downloader owns
 * the notification, the retry and the storage, and it is the only path that behaves the way users
 * expect a download to behave.
 *
 * Whether a download is accepted at all is `DownloadCoordinator`'s decision, not this class's.
 */
class AndroidDownloadEnqueuer(context: Context) : DownloadEnqueuer {

    private val appContext = context.applicationContext

    override fun enqueue(request: DownloadRequest) {
        runCatching {
            val name = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
            val dmRequest = DownloadManager.Request(Uri.parse(request.url)).apply {
                setMimeType(request.mimeType)
                request.userAgent?.let { addRequestHeader("User-Agent", it) }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDescription("GeoAlign download")
            }
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(dmRequest)
        }
    }
}
