package cc.kousen.kiosk

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class KioskConfig(
    val profile: String,
    val name: String,
    val homeUrl: String,
    val allowedOrigins: List<String>,
    val allowOfflineCache: Boolean,
    val leftEdgeHomeGestureEnabled: Boolean,
    val bottomEdgeHomeGestureEnabled: Boolean,
) {
    val homeUri: Uri = Uri.parse(homeUrl)

    fun isAllowedNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = Uri.parse(url)
        return isAllowedNavigation(uri)
    }

    fun isAllowedNavigation(uri: Uri): Boolean {
        if (!uri.isHierarchical) return false

        val candidateOrigin = uri.originString() ?: return false
        return allowedOrigins.any { allowed ->
            candidateOrigin.equals(allowed, ignoreCase = true)
        }
    }

    fun normalized(): KioskConfig {
        val normalizedHome = homeUri.normalizedHttpsUrl()
        val homeOrigin = Uri.parse(normalizedHome).originString()
            ?: error("homeUrl must include an HTTPS origin")

        val origins = (allowedOrigins + homeOrigin)
            .mapNotNull { Uri.parse(it).allowedOriginStringOrNull() }
            .distinctBy { it.lowercase() }

        require(normalizedHome.startsWith("https://")) { "homeUrl must use https" }
        require(origins.isNotEmpty()) {
            "allowedOrigins must include at least one HTTPS or private/local HTTP origin"
        }

        return copy(homeUrl = normalizedHome, allowedOrigins = origins)
    }

    fun toJson(): String = JSONObject()
        .put("profile", profile)
        .put("name", name)
        .put("homeUrl", homeUrl)
        .put("allowedOrigins", JSONArray(allowedOrigins))
        .put("allowOfflineCache", allowOfflineCache)
        .put("leftEdgeHomeGestureEnabled", leftEdgeHomeGestureEnabled)
        .put("bottomEdgeHomeGestureEnabled", bottomEdgeHomeGestureEnabled)
        .toString()

    companion object {
        val default = KioskConfig(
            profile = "kids",
            name = "Kousen Kids",
            homeUrl = "https://kousen.kids",
            allowedOrigins = listOf("https://kousen.kids"),
            allowOfflineCache = true,
            leftEdgeHomeGestureEnabled = true,
            bottomEdgeHomeGestureEnabled = true,
        )

        fun fromJson(json: String): KioskConfig {
            val obj = JSONObject(json)
            val origins = obj.optJSONArray("allowedOrigins") ?: JSONArray()
            return KioskConfig(
                profile = obj.optString("profile", default.profile),
                name = obj.optString("name", default.name),
                homeUrl = obj.optString("homeUrl", default.homeUrl),
                allowedOrigins = List(origins.length()) { index -> origins.getString(index) },
                allowOfflineCache = obj.optBoolean("allowOfflineCache", true),
                leftEdgeHomeGestureEnabled = obj.optBoolean("leftEdgeHomeGestureEnabled", true),
                bottomEdgeHomeGestureEnabled = obj.optBoolean("bottomEdgeHomeGestureEnabled", true),
            ).normalized()
        }

        fun fromIntent(intent: Intent, fallback: KioskConfig): KioskConfig {
            val configJson = intent.getStringExtra("configJson")
            if (!configJson.isNullOrBlank()) return fromJson(configJson)

            val homeUrl = intent.getStringExtra("homeUrl") ?: fallback.homeUrl
            val allowedOrigins = intent.getStringExtra("allowedOrigins")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: fallback.allowedOrigins

            return KioskConfig(
                profile = intent.getStringExtra("profile") ?: fallback.profile,
                name = intent.getStringExtra("name") ?: fallback.name,
                homeUrl = homeUrl,
                allowedOrigins = allowedOrigins,
                allowOfflineCache = intent.getBooleanExtra("allowOfflineCache", fallback.allowOfflineCache),
                leftEdgeHomeGestureEnabled = intent.getBooleanExtra(
                    "leftEdgeHomeGestureEnabled",
                    fallback.leftEdgeHomeGestureEnabled,
                ),
                bottomEdgeHomeGestureEnabled = intent.getBooleanExtra(
                    "bottomEdgeHomeGestureEnabled",
                    fallback.bottomEdgeHomeGestureEnabled,
                ),
            ).normalized()
        }
    }
}

class KioskConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk_config", Context.MODE_PRIVATE)

    fun load(): KioskConfig {
        val json = prefs.getString(KEY_CONFIG_JSON, null)
        return if (json.isNullOrBlank()) {
            KioskConfig.default.normalized().also(::save)
        } else {
            runCatching { KioskConfig.fromJson(json) }
                .getOrElse { KioskConfig.default.normalized().also(::save) }
        }
    }

    fun save(config: KioskConfig) {
        prefs.edit()
            .putString(KEY_CONFIG_JSON, config.normalized().toJson())
            .apply()
    }

    companion object {
        private const val KEY_CONFIG_JSON = "config_json"
    }
}

private fun Uri.originString(): String? {
    val scheme = scheme?.lowercase() ?: return null
    val host = host?.lowercase() ?: return null
    val portPart = if (port != -1) ":$port" else ""
    return "$scheme://$host$portPart"
}

private fun Uri.allowedOriginStringOrNull(): String? {
    val normalizedOrigin = originString() ?: return null
    return when (scheme?.lowercase()) {
        "https" -> normalizedOrigin
        "http" -> if (host.isPrivateOrLocalHost()) normalizedOrigin else null
        else -> null
    }
}

private fun String?.isPrivateOrLocalHost(): Boolean {
    val host = this?.lowercase() ?: return false
    if (host == "localhost" || host.endsWith(".local")) return true

    val octets = host.split(".").mapNotNull { part -> part.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false

    return octets[0] == 10 ||
        octets[0] == 127 ||
        octets[0] == 169 && octets[1] == 254 ||
        octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 192 && octets[1] == 168
}

private fun Uri.normalizedHttpsUrl(): String {
    require(scheme == "https") { "URL must use https" }
    require(!host.isNullOrBlank()) { "URL must include a host" }
    return buildUpon()
        .scheme("https")
        .authority(host!!.lowercase() + if (port != -1) ":$port" else "")
        .build()
        .toString()
}
