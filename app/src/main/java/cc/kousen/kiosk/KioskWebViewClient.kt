package cc.kousen.kiosk

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
        Log.i(TAG, "Page started: $url")
        if (!configProvider().isAllowedNavigation(url)) {
            Log.w(TAG, "Stopping disallowed page start: $url")
            view.stopLoading()
            view.loadUrl(configProvider().homeUrl)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        Log.i(TAG, "Page finished: $url title=${view.title.orEmpty()} progress=${view.progress}")
        if (configProvider().isAllowedNavigation(url)) {
            onAllowedPageFinished(view)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: android.webkit.WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        val frame = if (request.isForMainFrame) "main-frame" else "subresource"
        Log.e(TAG, "Load error [$frame] ${request.url}: ${error.errorCode} ${error.description}")
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        val frame = if (request.isForMainFrame) "main-frame" else "subresource"
        Log.w(
            TAG,
            "HTTP error [$frame] ${request.url}: " +
                "${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}",
        )
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.e(
            TAG,
            "WebView renderer gone: didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}",
        )
        return false
    }

    companion object {
        private const val TAG = "KioskWebViewClient"
    }
}
