package cc.kousen.kiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
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
    private lateinit var adminTriggerController: AdminTriggerController
    private lateinit var navigationGestureController: KioskNavigationGestureController
    private lateinit var adminPinStore: AdminPinStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var config: KioskConfig = KioskConfig.default.normalized()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        configStore = KioskConfigStore(this)
        policyManager = KioskPolicyManager(this)
        textToSpeechBridge = KioskTextToSpeechBridge(this)
        adminPinStore = AdminPinStore(this)
        config = configStore.load()
        handleConfigIntent()
        handleAdminPinIntent()
        volumeControlStream = AudioManager.STREAM_MUSIC

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        configureServiceWorkers()
        installWebView()
        configureBackHandling()

        policyManager.applyDeviceOwnerKioskPolicies()
        if (!handleLoadUrlIntent() && !handleRefreshIntent()) {
            loadHome()
        }
        handleAdminIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyKioskPolicies()
        if (handleAdminPinIntent()) return
        if (handleAdminIntent()) return
        if (handleLoadUrlIntent()) return
        if (handleRefreshIntent()) return
        if (handleConfigIntent()) {
            loadHome()
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacksAndMessages(ADMIN_RETURN_TOKEN)
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

    private fun handleLoadUrlIntent(): Boolean {
        if (intent.action != ACTION_LOAD_URL) return false

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val uri = Uri.parse(targetUrl)
        if (!config.isAllowedNavigation(uri)) {
            Log.w(TAG, "Ignoring disallowed load-url intent: $targetUrl")
            onBlockedNavigation(uri)
            return true
        }

        Log.i(TAG, "Loading allowlisted URL from intent: $targetUrl")
        webView.loadUrl(targetUrl)
        return true
    }

    private fun handleAdminIntent(): Boolean {
        if (intent.action != ACTION_ADMIN) return false
        showAdminPinPrompt()
        return true
    }

    private fun handleAdminPinIntent(): Boolean {
        if (intent.action != ACTION_SET_ADMIN_PIN) return false

        val pin = intent.getStringExtra(EXTRA_ADMIN_PIN).orEmpty()
        val saved = runCatching {
            adminPinStore.save(pin)
        }.onFailure { error ->
            Log.w(TAG, "Ignoring invalid admin PIN intent", error)
            if (BuildConfig.DEBUG) {
                Toast.makeText(this, "Invalid admin PIN: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }.isSuccess

        if (saved && BuildConfig.DEBUG) {
            Toast.makeText(this, "Admin PIN updated.", Toast.LENGTH_SHORT).show()
        }
        return saved
    }

    private fun applyKioskPolicies() {
        policyManager.applyDeviceOwnerKioskPolicies()
        policyManager.startLockTaskIfPermitted(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun installWebView() {
        adminTriggerController = AdminTriggerController(::showAdminPinPrompt)
        navigationGestureController = KioskNavigationGestureController(
            onHomeGesture = ::loadHome,
            isLeftEdgeHomeGestureEnabled = { config.leftEdgeHomeGestureEnabled },
            isBottomEdgeHomeGestureEnabled = { config.bottomEdgeHomeGestureEnabled },
        )
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true

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
            setOnTouchListener { view, event ->
                hideSystemBars()
                val handledByNavigation = navigationGestureController.onTouch(view, event)
                val handledByAdmin = adminTriggerController.onTouch(view, event)
                handledByNavigation || handledByAdmin
            }
            setOnKeyListener { _, _, event ->
                hideSystemBars()
                adminTriggerController.onKeyEvent(event)
            }
            requestFocus()
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
        window.decorView.systemUiVisibility = IMMERSIVE_SYSTEM_UI_FLAGS
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
        window.decorView.systemUiVisibility = IMMERSIVE_SYSTEM_UI_FLAGS
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

    private fun showAdminPinPrompt() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setSingleLine(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Admin Mode")
            .setMessage("Enter the admin PIN.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (adminPinStore.verify(input.text.toString())) {
                    dialog.dismiss()
                    showAdminPanel()
                } else {
                    input.text?.clear()
                    input.error = "Incorrect PIN"
                }
            }
        }
        dialog.setOnDismissListener { hideSystemBars() }
        dialog.show()
    }

    private fun showAdminPanel() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val currentUrl = webView.url ?: config.homeUrl
        content.addView(
            TextView(this).apply {
                text = adminDiagnosticsText(currentUrl)
                textSize = 14f
            },
        )

        val brightnessLabel = TextView(this).apply {
            text = "Brightness"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        content.addView(brightnessLabel)

        val brightnessSeekBar = SeekBar(this).apply {
            max = 100
            progress = getCurrentBrightnessPercent()
        }
        content.addView(brightnessSeekBar)

        val leftEdgeHomeGestureCheckBox = CheckBox(this).apply {
            text = "Left-edge swipe returns home"
            isChecked = config.leftEdgeHomeGestureEnabled
        }
        val bottomEdgeHomeGestureCheckBox = CheckBox(this).apply {
            text = "Bottom-edge swipe up returns home"
            isChecked = config.bottomEdgeHomeGestureEnabled
        }
        content.addView(leftEdgeHomeGestureCheckBox)
        content.addView(bottomEdgeHomeGestureCheckBox)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kousen Kiosk Admin")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .create()

        content.addView(adminButton("Apply Brightness") {
            val applied = policyManager.setScreenBrightness(brightnessSeekBar.progress.coerceAtLeast(1))
            Toast.makeText(
                this,
                if (applied) "Brightness updated." else "Brightness could not be updated.",
                Toast.LENGTH_SHORT,
            ).show()
        })
        content.addView(adminButton("Save Gesture Settings") {
            saveConfig(
                config.copy(
                    leftEdgeHomeGestureEnabled = leftEdgeHomeGestureCheckBox.isChecked,
                    bottomEdgeHomeGestureEnabled = bottomEdgeHomeGestureCheckBox.isChecked,
                ),
                refreshOriginScopedScripts = false,
            )
            Toast.makeText(this, "Gesture settings saved.", Toast.LENGTH_SHORT).show()
        })
        content.addView(adminButton("Homepage And Allowlist") {
            showHomepageDialog(dialog)
        })
        content.addView(adminButton("Reload Page") {
            refreshWebContent(clearCache = false, clearWebStorage = false)
            dialog.dismiss()
        })
        content.addView(adminButton("Reset Web Media State") {
            resetWebMediaState()
        })
        content.addView(adminButton("Clear Cache And Reload") {
            refreshWebContent(clearCache = true, clearWebStorage = false)
            dialog.dismiss()
        })
        content.addView(adminButton("Clear Site Storage And Reload") {
            AlertDialog.Builder(this)
                .setTitle("Clear Site Storage?")
                .setMessage("This can remove local progress, IndexedDB, and other saved website state.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    refreshWebContent(clearCache = true, clearWebStorage = true)
                    dialog.dismiss()
                }
                .show()
        })
        content.addView(adminButton("Open Wi-Fi Settings") {
            dialog.dismiss()
            openWifiSettingsForAdmin()
        })
        content.addView(adminButton("Open Bluetooth Settings") {
            dialog.dismiss()
            openBluetoothSettingsForAdmin()
        })

        dialog.setOnDismissListener { hideSystemBars() }
        dialog.show()
    }

    private fun adminDiagnosticsText(currentUrl: String): String =
        listOf(
            "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                if (BuildConfig.DEBUG) "debug" else "release",
            "Profile: ${config.name}",
            "Home: ${config.homeUrl}",
            "Current: $currentUrl",
            "Allowed: ${config.allowedOrigins.joinToString(",")}",
            "Lock Task: ${if (policyManager.isInLockTaskMode()) "locked" else "unlocked"}",
            "Device Owner: ${if (policyManager.isDeviceOwner()) "yes" else "no"}",
        ).joinToString("\n")

    private fun showHomepageDialog(parentDialog: AlertDialog) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val profileInput = adminTextInput("Profile", config.profile)
        val nameInput = adminTextInput("Name", config.name)
        val homeUrlInput = adminTextInput("Home URL", config.homeUrl)
        val allowedHttpsOriginsInput = adminTextInput(
            "Allowed HTTPS origins, comma-separated",
            config.allowedOrigins
                .filter { it.startsWith("https://", ignoreCase = true) }
                .joinToString(","),
        )
        val allowedPrivateHttpOriginsInput = adminTextInput(
            "Allowed private/local HTTP origins, comma-separated",
            config.allowedOrigins
                .filter { it.startsWith("http://", ignoreCase = true) }
                .joinToString(","),
        )
        fields.addView(adminButton("Use Kousen Kids") {
            profileInput.setText("kids")
            nameInput.setText("Kousen Kids")
            homeUrlInput.setText("https://kousen.kids")
            allowedHttpsOriginsInput.setText("https://kousen.kids")
            allowedPrivateHttpOriginsInput.setText("")
        })
        fields.addView(adminButton("Use Kousen Command Center") {
            profileInput.setText("command-center")
            nameInput.setText("Kousen Command Center")
            homeUrlInput.setText("https://kousen.cc")
            allowedHttpsOriginsInput.setText("https://kousen.cc")
            allowedPrivateHttpOriginsInput.setText("http://192.168.10.10:8000,http://192.168.10.10:32400")
        })
        fields.addView(adminButton("Use Kousen Games") {
            profileInput.setText("games")
            nameInput.setText("Kousen Games")
            homeUrlInput.setText("https://kousen.games")
            allowedHttpsOriginsInput.setText("https://kousen.games")
            allowedPrivateHttpOriginsInput.setText("")
        })
        fields.addView(adminButton("Allow YouTube HTTPS") {
            allowedHttpsOriginsInput.setText(
                mergeCommaSeparatedOrigins(
                    allowedHttpsOriginsInput.text.toString(),
                    listOf(
                        "https://youtube.com",
                        "https://www.youtube.com",
                        "https://m.youtube.com",
                        "https://youtu.be",
                    ),
                ),
            )
        })
        fields.addView(profileInput)
        fields.addView(nameInput)
        fields.addView(homeUrlInput)
        fields.addView(allowedHttpsOriginsInput)
        fields.addView(allowedPrivateHttpOriginsInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Homepage And Allowlist")
            .setMessage(
                "Home URL must be HTTPS. Allowlist entries are exact origins; " +
                    "HTTP is limited to private/local network origins.",
            )
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = runCatching {
                    KioskConfig(
                        profile = profileInput.text.toString().ifBlank { config.profile },
                        name = nameInput.text.toString().ifBlank { config.name },
                        homeUrl = homeUrlInput.text.toString(),
                        allowedOrigins = parseCommaSeparatedOrigins(
                            allowedHttpsOriginsInput.text.toString(),
                            allowedPrivateHttpOriginsInput.text.toString(),
                        ),
                        allowOfflineCache = config.allowOfflineCache,
                        leftEdgeHomeGestureEnabled = config.leftEdgeHomeGestureEnabled,
                        bottomEdgeHomeGestureEnabled = config.bottomEdgeHomeGestureEnabled,
                    ).normalized()
                }.onFailure { error ->
                    homeUrlInput.error = error.message ?: "Invalid homepage"
                }.getOrNull() ?: return@setOnClickListener

                saveConfig(updated)
                refreshWebContent(clearCache = true, clearWebStorage = false)
                dialog.dismiss()
                parentDialog.dismiss()
                Toast.makeText(this, "Homepage updated.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setOnDismissListener { hideSystemBars() }
        dialog.show()
    }

    private fun saveConfig(
        updated: KioskConfig,
        refreshOriginScopedScripts: Boolean = true,
    ) {
        config = updated.normalized()
        configStore.save(config)
        if (refreshOriginScopedScripts) {
            configureServiceWorkers()
            installDocumentStartSpeechSynthesisShim(webView)
        }
    }

    private fun parseCommaSeparatedOrigins(vararg values: String): List<String> =
        values
            .flatMap { value -> value.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun mergeCommaSeparatedOrigins(current: String, additions: List<String>): String =
        parseCommaSeparatedOrigins(current, additions.joinToString(","))
            .distinctBy { it.lowercase() }
            .joinToString(",")

    private fun resetWebMediaState() {
        val script = """
            (() => {
              const result = { storageKeys: [], mediaCount: 0, error: null };
              try {
                localStorage.setItem("kousentv.masterVolume", "1");
                result.storageKeys.push("kousentv.masterVolume");
              } catch (error) {
                result.error = String(error);
              }
              document.querySelectorAll("audio,video").forEach((media) => {
                media.muted = false;
                media.defaultMuted = false;
                media.volume = 1;
                result.mediaCount += 1;
              });
              return result;
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { value ->
            Log.i(TAG, "Reset web media state result: $value")
            Toast.makeText(this, "Web media state reset.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun adminTextInput(label: String, value: String): EditText =
        EditText(this).apply {
            hint = label
            setText(value)
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

    private fun adminButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun getCurrentBrightnessPercent(): Int {
        val brightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(DEFAULT_SCREEN_BRIGHTNESS)
        return ((brightness / MAX_SCREEN_BRIGHTNESS.toFloat()) * 100).toInt().coerceIn(1, 100)
    }

    private fun openWifiSettingsForAdmin() {
        policyManager.temporarilyRelaxForAdminSettings()
        runCatching { stopLockTask() }

        val launched = runCatching {
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }.isSuccess

        if (!launched) {
            Toast.makeText(this, "Wi-Fi settings could not be opened.", Toast.LENGTH_SHORT).show()
            policyManager.reapplyFullPolicyOnNextResume()
            applyKioskPolicies()
            return
        }

        mainHandler.postDelayed(
            {
                policyManager.reapplyFullPolicyOnNextResume()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            },
            ADMIN_RETURN_TOKEN,
            ADMIN_SETTINGS_RETURN_DELAY_MS,
        )
    }

    private fun openBluetoothSettingsForAdmin() {
        policyManager.temporarilyRelaxForAdminSettings()
        runCatching { stopLockTask() }

        val launched = runCatching {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }.isSuccess

        if (!launched) {
            Toast.makeText(this, "Bluetooth settings could not be opened.", Toast.LENGTH_SHORT).show()
            policyManager.reapplyFullPolicyOnNextResume()
            applyKioskPolicies()
            return
        }

        mainHandler.postDelayed(
            {
                policyManager.reapplyFullPolicyOnNextResume()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            },
            ADMIN_RETURN_TOKEN,
            ADMIN_SETTINGS_RETURN_DELAY_MS,
        )
    }

    companion object {
        private const val TAG = "KousenKiosk"
        private const val SYSTEM_BARS_REHIDE_DELAY_MS = 1_000L
        private const val ADMIN_SETTINGS_RETURN_DELAY_MS = 2 * 60 * 1_000L
        private const val MAX_SCREEN_BRIGHTNESS = 255
        private const val DEFAULT_SCREEN_BRIGHTNESS = 180
        private const val IMMERSIVE_SYSTEM_UI_FLAGS =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        private val HIDE_SYSTEM_BARS_TOKEN = Any()
        private val ADMIN_RETURN_TOKEN = Any()
        const val ACTION_SET_CONFIG = "cc.kousen.kiosk.action.SET_CONFIG"
        const val ACTION_REFRESH = "cc.kousen.kiosk.action.REFRESH"
        const val ACTION_LOAD_URL = "cc.kousen.kiosk.action.LOAD_URL"
        const val ACTION_ADMIN = "cc.kousen.kiosk.action.ADMIN"
        const val ACTION_SET_ADMIN_PIN = "cc.kousen.kiosk.action.SET_ADMIN_PIN"
        const val EXTRA_URL = "url"
        const val EXTRA_CLEAR_CACHE = "clearCache"
        const val EXTRA_CLEAR_WEB_STORAGE = "clearWebStorage"
        const val EXTRA_ADMIN_PIN = "pin"
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
