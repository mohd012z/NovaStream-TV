package com.novastream.tv

import android.content.Context

class PlayerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_player_settings", Context.MODE_PRIVATE)
    var brightnessSensitivity: Float
        get() = prefs.getFloat("brightness", 0.30f)
        set(value) = prefs.edit().putFloat("brightness", value.coerceIn(0.10f, 0.80f)).apply()
    var volumeSensitivity: Float
        get() = prefs.getFloat("volume", 0.50f)
        set(value) = prefs.edit().putFloat("volume", value.coerceIn(0.10f, 1.0f)).apply()
    var autoRetry: Boolean
        get() = prefs.getBoolean("auto_retry", true)
        set(value) = prefs.edit().putBoolean("auto_retry", value).apply()
}
