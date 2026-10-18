package com.jarvis.one

import android.content.Context
import android.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppPrefs {
    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    const val DEFAULT_PROMPT = "You are Jarvis, a short, friendly assistant. Reply in the user's language (Urdu, Roman Urdu, Hindi, or English). Keep answers concise. When the user asks for a phone action, reply with a short explanation followed by exactly one fenced ```action JSON block, with valid JSON and no other action block. Examples: open app {\"type\":\"open_app\",\"name\":\"WhatsApp\"}; YouTube search {\"type\":\"youtube\",\"query\":\"...\"}; back/home {\"type\":\"back\"} or {\"type\":\"home\"}; tap {\"type\":\"tap\",\"x\":100,\"y\":300}; swipe {\"type\":\"swipe\",\"x1\":100,\"y1\":500,\"x2\":400,\"y2\":500}; read screen {\"type\":\"read_screen\"}; start game automation {\"type\":\"game_start\",\"game\":\"Minecraft\",\"goal\":\"mine wood\"}; stop it {\"type\":\"game_stop\"}; music {\"type\":\"music\",\"query\":\"...\"}; media controls {\"type\":\"media\",\"cmd\":\"pause|play|next|prev|volume_up|volume_down\"}; phone call {\"type\":\"call\",\"name\":\"...\"}; SMS {\"type\":\"sms\",\"name\":\"...\",\"text\":\"...\"}; WhatsApp {\"type\":\"whatsapp\",\"name\":\"...\",\"text\":\"...\"}; alarm {\"type\":\"alarm\",\"hour\":7,\"minute\":30}; timer {\"type\":\"timer\",\"seconds\":300}; reminder {\"type\":\"reminder\",\"text\":\"...\",\"minutes\":10}; navigation {\"type\":\"navigate\",\"place\":\"...\"}; web search {\"type\":\"search\",\"query\":\"...\"}; URL {\"type\":\"open_url\",\"url\":\"https://...\"}; notes {\"type\":\"notes\",\"op\":\"save|show|read\",\"text\":\"...\"}; routine {\"type\":\"routine\",\"name\":\"good morning\"}. Other supported actions: flashlight, volume, brightness, wifi, bluetooth, open_settings, and battery. Calls and SMS always require confirmation."

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(context: Context, key: String, fallback: String = ""): String =
        prefs(context).getString(key, fallback) ?: fallback

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun bool(context: Context, key: String, fallback: Boolean = false): Boolean =
        prefs(context).getBoolean(key, fallback)

    fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun accentColor(context: Context): Int = when (get(context, "accent", "Blue")) {
        "Purple" -> Color.rgb(149, 92, 246)
        "Teal" -> Color.rgb(0, 150, 136)
        "Orange" -> Color.rgb(239, 126, 46)
        else -> Color.rgb(57, 120, 246)
    }

    fun backgroundColor(context: Context): Int =
        if (bool(context, "light_theme")) Color.rgb(248, 249, 252) else Color.rgb(16, 19, 26)

    fun surfaceColor(context: Context): Int =
        if (bool(context, "light_theme")) Color.rgb(232, 236, 244) else Color.rgb(34, 40, 52)

    fun textColor(context: Context): Int =
        if (bool(context, "light_theme")) Color.rgb(28, 33, 42) else Color.WHITE

    fun mutedTextColor(context: Context): Int =
        if (bool(context, "light_theme")) Color.rgb(90, 98, 112) else Color.LTGRAY

    fun systemPrompt(context: Context): String {
        val custom = get(context, "system_prompt").trim()
        if (custom.isNotEmpty()) return custom
        val persona = get(context, "persona", "Jarvis")
        val style = when (persona) {
            "Friendly" -> "Be especially warm and conversational."
            "Girlfriend (Yasmin style)" -> "Be affectionate, supportive, and respectful; do not claim to be human."
            "Coding Teacher" -> "Teach patiently and explain code simply."
            else -> ""
        }
        return "$DEFAULT_PROMPT $style"
    }
}
