package cc.kousen.kiosk

import android.annotation.SuppressLint
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var configStore: KioskConfigStore
    private lateinit var policyManager: KioskPolicyManager
    private lateinit var textToSpeechBridge: KioskTextToSpeechBridge
    private val mainHandler = Handler(Looper.getMainLooper())
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
        if (!handleRefreshIntent()) {
            loadHome()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyKioskPolicies()
        if (handleRefreshIntent()) return
        if (handleConfigIntent()) {
            loadHome()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyKioskPolicies()
        webView.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
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

    private fun handleRefreshIntent(): Boolean {
        if (intent.action != ACTION_REFRESH) return false
        refreshWebContent(
            clearCache = intent.getBooleanExtra(EXTRA_CLEAR_CACHE, true),
            clearWebStorage = intent.getBooleanExtra(EXTRA_CLEAR_WEB_STORAGE, false),
        )
        return true
    }

    private fun applyKioskPolicies() {
        policyManager.applyDeviceOwnerKioskPolicies()
        policyManager.startLockTaskIfPermitted(this)
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
            )
            webChromeClient = KioskWebChromeClient()
            val adminModeController = AdminModeController(::onAdminGesture)
            setOnTouchListener { view, event ->
                hideSystemBars()
                adminModeController.onTouch(view, event)
            }
        }

        setContentView(webView)
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

    private fun loadHome() {
        webView.loadUrl(config.homeUrl)
    }

    private fun refreshWebContent(clearCache: Boolean, clearWebStorage: Boolean) {
        if (clearWebStorage) {
            WebStorage.getInstance().deleteAllData()
        }
        if (clearCache) {
            webView.clearCache(true)
        }
        webView.loadUrl(config.homeUrl)
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
                        loadHome()
                    } else {
                        loadHome()
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
        mainHandler.removeCallbacksAndMessages(HIDE_SYSTEM_BARS_TOKEN)
        mainHandler.postDelayed(
            { hideSystemBarsOnce() },
            HIDE_SYSTEM_BARS_TOKEN,
            SYSTEM_BARS_REHIDE_DELAY_MS,
        )
    }

    private fun hideSystemBarsOnce() {
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
        refreshWebContent(clearCache = true, clearWebStorage = false)
        if (BuildConfig.DEBUG) {
            Toast.makeText(
                this,
                "Refreshing Kousen Kiosk content.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "KousenKiosk"
        private const val SYSTEM_BARS_REHIDE_DELAY_MS = 1_000L
        private val HIDE_SYSTEM_BARS_TOKEN = Any()
        const val ACTION_SET_CONFIG = "cc.kousen.kiosk.action.SET_CONFIG"
        const val ACTION_REFRESH = "cc.kousen.kiosk.action.REFRESH"
        const val EXTRA_CLEAR_CACHE = "clearCache"
        const val EXTRA_CLEAR_WEB_STORAGE = "clearWebStorage"
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
