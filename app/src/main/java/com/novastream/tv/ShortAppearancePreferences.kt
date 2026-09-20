package com.novastream.tv

import android.content.Context

enum class ShortFontFamily { SYSTEM, SANS_SERIF, SERIF, MONOSPACE }

data class ShortAppearance(
    val titleSizeSp: Float = 20f,
    val infoSizeSp: Float = 13f,
    val fontFamily: ShortFontFamily = ShortFontFamily.SYSTEM,
    val textColorArgb: Long = 0xFFFFFFFF,
    val infoColorArgb: Long = 0xFF67D6FF
)

class ShortAppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_short_appearance", Context.MODE_PRIVATE)

    fun load(): ShortAppearance = ShortAppearance(
        titleSizeSp = prefs.getFloat("title_size_sp", 20f).coerceIn(10f, 72f),
        infoSizeSp = prefs.getFloat("info_size_sp", 13f).coerceIn(10f, 72f),
        fontFamily = runCatching {
            ShortFontFamily.valueOf(prefs.getString("font_family", ShortFontFamily.SYSTEM.name)!!)
        }.getOrDefault(ShortFontFamily.SYSTEM),
        textColorArgb = prefs.getLong("text_color_argb", 0xFFFFFFFF),
        infoColorArgb = prefs.getLong("info_color_argb", 0xFF67D6FF)
    )

    fun save(value: ShortAppearance) {
        prefs.edit()
            .putFloat("title_size_sp", value.titleSizeSp.coerceIn(10f, 72f))
            .putFloat("info_size_sp", value.infoSizeSp.coerceIn(10f, 72f))
            .putString("font_family", value.fontFamily.name)
            .putLong("text_color_argb", value.textColorArgb)
            .putLong("info_color_argb", value.infoColorArgb)
            .apply()
    }
}
