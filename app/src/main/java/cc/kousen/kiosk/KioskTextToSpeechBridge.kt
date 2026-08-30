package cc.kousen.kiosk

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class KioskTextToSpeechBridge(
    context: Context,
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val textToSpeech = TextToSpeech(appContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            textToSpeech.language = Locale.getDefault()
            Log.i(TAG, "Android TextToSpeech initialized")
        } else {
            Log.w(TAG, "Android TextToSpeech failed to initialize: $status")
        }
    }

    @JavascriptInterface
    fun isReady(): Boolean = ready

    @JavascriptInterface
    fun speak(payloadJson: String) {
        if (!ready) {
            Log.w(TAG, "Ignoring speech request before TextToSpeech is ready")
            return
        }

        val payload = runCatching { JSONObject(payloadJson) }
            .onFailure { Log.w(TAG, "Invalid speech payload", it) }
            .getOrNull() ?: return

        val text = payload.optString("text").trim()
        if (text.isBlank()) return
        Log.i(TAG, "Speaking ${text.length} characters with Android TextToSpeech")

        payload.optString("lang").takeIf { it.isNotBlank() }?.let { languageTag ->
            runCatching {
                textToSpeech.language = Locale.forLanguageTag(languageTag)
            }.onFailure { error ->
                Log.w(TAG, "Unable to set TTS language: $languageTag", error)
            }
        }

        val rate = payload.optDouble("rate", 1.0).toFloat().coerceIn(0.1f, 3.0f)
        val pitch = payload.optDouble("pitch", 1.0).toFloat().coerceIn(0.1f, 3.0f)
        textToSpeech.setSpeechRate(rate)
        textToSpeech.setPitch(pitch)

        val utteranceId = payload.optString("utteranceId", UUID.randomUUID().toString())
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, utteranceId)
    }

    @JavascriptInterface
    fun cancel() {
        textToSpeech.stop()
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    companion object {
        const val JAVASCRIPT_INTERFACE_NAME = "KousenNativeTts"
        private const val TAG = "KioskTextToSpeech"
    }
}
