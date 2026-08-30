package cc.kousen.kiosk

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class KioskWebViewClient(
    private val configProvider: () -> KioskConfig,
    private val onBlockedNavigation: (Uri) -> Unit,
    private val onAllowedPageFinished: (WebView) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!request.isForMainFrame) return false

        val uri = request.url
        val allowed = configProvider().isAllowedNavigation(uri)
        if (!allowed) {
            Log.w(TAG, "Blocked navigation to $uri")
            onBlockedNavigation(uri)
        }
        return !allowed
    }

    @Deprecated("Required for older WebView callback paths.")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        val uri = Uri.parse(url)
        val allowed = configProvider().isAllowedNavigation(uri)
        if (!allowed) {
            Log.w(TAG, "Blocked navigation to $uri")
            onBlockedNavigation(uri)
        }
        return !allowed
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url == "about:blank") return
        if (!configProvider().isAllowedNavigation(url)) {
            view.stopLoading()
            view.loadUrl(configProvider().homeUrl)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (configProvider().isAllowedNavigation(url)) {
            onAllowedPageFinished(view)
        }
    }

    companion object {
        private const val TAG = "KioskWebViewClient"
    }
}
