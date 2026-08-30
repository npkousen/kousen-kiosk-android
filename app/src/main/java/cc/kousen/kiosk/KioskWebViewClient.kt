package cc.kousen.kiosk

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class KioskWebViewClient(
    private val configProvider: () -> KioskConfig,
    private val onBlockedNavigation: (Uri) -> Unit,
    private val onAllowedPageFinished: (WebView) -> Unit,
    private val onMainFrameVisible: () -> Unit,
    private val onMainFrameError: (String) -> Unit,
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

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
        if (configProvider().isAllowedNavigation(url)) {
            onMainFrameVisible()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            val message = "Page load failed: ${error.errorCode} ${error.description}"
            Log.w(TAG, message)
            onMainFrameError(message)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            val message = "Page returned HTTP ${errorResponse.statusCode}"
            Log.w(TAG, message)
            onMainFrameError(message)
        }
    }

    companion object {
        private const val TAG = "KioskWebViewClient"
    }
}
