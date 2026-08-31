package cc.kousen.kiosk

import android.content.Context

class AdminPinStore(context: Context) {
    private val prefs = context.getSharedPreferences("admin_pin", Context.MODE_PRIVATE)

    fun verify(pin: String): Boolean = pin == getPin()

    fun save(pin: String) {
        require(pin.matches(PIN_PATTERN)) { "PIN must be 4-8 digits" }
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    private fun getPin(): String = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    companion object {
        const val DEFAULT_PIN = "2468"
        private const val KEY_PIN = "pin"
        private val PIN_PATTERN = Regex("\\d{4,8}")
    }
}
