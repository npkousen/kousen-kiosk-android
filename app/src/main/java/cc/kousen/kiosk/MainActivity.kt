package cc.kousen.kiosk

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var configStore: KioskConfigStore
    private lateinit var policyManager: KioskPolicyManager
    private lateinit var textToSpeechBridge: KioskTextToSpeechBridge
    private var config: KioskConfig = KioskConfig.default.normalized()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        configStore = KioskConfigStore(this)
        policyManager = KioskPolicyManager(this)
        textToSpeechBridge = KioskTextToSpeechBridge(this)
        config = configStore.load()
        handleConfigIntent()
        volumeControlStream = AudioManager.STREAM_MUSIC

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        configureServiceWorkers()
        installWebView()
        configureBackHandling()

        policyManager.applyDeviceOwnerKioskPolicies()
        webView.loadUrl(config.homeUrl)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleConfigIntent()) {
            webView.loadUrl(config.homeUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        policyManager.startLockTaskIfPermitted(this)
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        textToSpeechBridge.shutdown()
        super.onDestroy()
    }

    private fun handleConfigIntent(): Boolean {
        if (intent.action != ACTION_SET_CONFIG) return false

        val nextConfig = runCatching {
            KioskConfig.fromIntent(intent, config)
        }.onFailure { error ->
            Log.w(TAG, "Ignoring invalid kiosk config intent", error)
            if (BuildConfig.DEBUG) {
                Toast.makeText(this, "Invalid kiosk config: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }.getOrNull() ?: return false

        config = nextConfig
        configStore.save(nextConfig)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun installWebView() {
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = if (config.allowOfflineCache) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_NO_CACHE
            }
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            addJavascriptInterface(
                textToSpeechBridge,
                KioskTextToSpeechBridge.JAVASCRIPT_INTERFACE_NAME,
            )
            installDocumentStartSpeechSynthesisShim(this)

            webViewClient = KioskWebViewClient(
                configProvider = { config },
                onBlockedNavigation = ::onBlockedNavigation,
                onAllowedPageFinished = ::installSpeechSynthesisShim,
                onMainFrameVisible = ::hideStatus,
                onMainFrameError = ::showLoadError,
            )
            webChromeClient = KioskWebChromeClient()
            setOnTouchListener(AdminModeController(::onAdminGesture))
        }

        statusView = TextView(this).apply {
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.rgb(31, 41, 55))
            gravity = Gravity.CENTER
            textSize = 16f
            text = "Loading Kousen Kiosk..."
            setOnClickListener {
                showLoading()
                webView.loadUrl(config.homeUrl)
            }
        }

        root = FrameLayout(this).apply {
            addView(webView)
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        setContentView(root)
    }

    private fun installDocumentStartSpeechSynthesisShim(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "Document-start JavaScript injection is not supported by this WebView")
            return
        }

        runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                KioskSpeechSynthesisShim.script,
                config.allowedOrigins.toSet(),
            )
            Log.i(TAG, "Installed document-start speech synthesis shim")
        }.onFailure { error ->
            Log.w(TAG, "Unable to install document-start speech synthesis shim", error)
        }
    }

    private fun installSpeechSynthesisShim(view: WebView) {
        view.evaluateJavascript(KioskSpeechSynthesisShim.script, null)
    }

    private fun showLoading() {
        statusView.text = "Loading Kousen Kiosk..."
        statusView.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusView.visibility = View.GONE
    }

    private fun showLoadError(message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, message)
        }
        statusView.text = "Unable to load kiosk page.\nTap to retry."
        statusView.visibility = View.VISIBLE
    }

    private fun configureServiceWorkers() {
        val settings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = false
        settings.cacheMode = if (config.allowOfflineCache) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_NO_CACHE
        }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else if (webView.url != config.homeUrl) {
                        webView.loadUrl(config.homeUrl)
                    } else {
                        webView.loadUrl(config.homeUrl)
                    }
                }
            },
        )
    }

    private fun hideSystemBars() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun onBlockedNavigation(uri: Uri) {
        if (BuildConfig.DEBUG) {
            Toast.makeText(this, "Blocked: ${uri.scheme ?: "unknown"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAdminGesture() {
        if (BuildConfig.DEBUG) {
            Toast.makeText(
                this,
                "Admin gesture detected. Parent mode is not implemented in v0.1.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "KousenKiosk"
        const val ACTION_SET_CONFIG = "cc.kousen.kiosk.action.SET_CONFIG"
    }
}

private class KioskWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
            )
        }
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        Log.w(TAG, "Denying WebView permission request: ${request.resources.joinToString()}")
        request.deny()
    }

    companion object {
        private const val TAG = "KioskWebChrome"
    }
}
