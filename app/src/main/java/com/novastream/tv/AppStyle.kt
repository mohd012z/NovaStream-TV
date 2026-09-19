package com.novastream.tv

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

enum class NovaThemePreset(val label: String) {
    NEON("Nova Neon"), OCEAN("Ocean Blue"), VIOLET("Violet Cinema"),
    EMERALD("Emerald"), SUNSET("Sunset"), MONO("Midnight Mono")
}
enum class NovaFontPreset(val label: String) { MODERN("Modern"), CLEAN("Clean"), CLASSIC("Classic") }
enum class NovaIconStyle(val label: String) { ROUNDED("Rounded"), COMPACT("Compact"), BOLD("Bold") }

data class NovaPalette(
    val accent: Color, val accent2: Color, val bg: Color,
    val panel: Color, val panel2: Color, val muted: Color
)

fun paletteFor(preset: NovaThemePreset): NovaPalette = when (preset) {
    NovaThemePreset.NEON -> NovaPalette(Color(0xFF67D6FF), Color(0xFF78F1C7), Color(0xFF080B10), Color(0xFF121924), Color(0xFF1A2633), Color(0xFFA8B3C0))
    NovaThemePreset.OCEAN -> NovaPalette(Color(0xFF38BDF8), Color(0xFF22D3EE), Color(0xFF06121E), Color(0xFF0C1D2C), Color(0xFF123047), Color(0xFF9FB4C6))
    NovaThemePreset.VIOLET -> NovaPalette(Color(0xFFA78BFA), Color(0xFFF0ABFC), Color(0xFF0D0915), Color(0xFF1A1228), Color(0xFF2A1D3D), Color(0xFFBDB2CC))
    NovaThemePreset.EMERALD -> NovaPalette(Color(0xFF34D399), Color(0xFF5EEAD4), Color(0xFF06110E), Color(0xFF0D211A), Color(0xFF15342A), Color(0xFFA8C4B8))
    NovaThemePreset.SUNSET -> NovaPalette(Color(0xFFFB7185), Color(0xFFFBBF24), Color(0xFF130A0D), Color(0xFF251317), Color(0xFF3B1D23), Color(0xFFC8AEB3))
    NovaThemePreset.MONO -> NovaPalette(Color(0xFFE5E7EB), Color(0xFF94A3B8), Color(0xFF050607), Color(0xFF111315), Color(0xFF1C2024), Color(0xFFA9B0B8))
}

fun fontFor(preset: NovaFontPreset): FontFamily = when (preset) {
    NovaFontPreset.MODERN -> FontFamily.SansSerif
    NovaFontPreset.CLEAN -> FontFamily.Default
    NovaFontPreset.CLASSIC -> FontFamily.Serif
}

class AppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_appearance", Context.MODE_PRIVATE)
    var theme: NovaThemePreset
        get() = runCatching { NovaThemePreset.valueOf(prefs.getString("theme", NovaThemePreset.NEON.name)!!) }.getOrDefault(NovaThemePreset.NEON)
        set(v) = prefs.edit().putString("theme", v.name).apply()
    var font: NovaFontPreset
        get() = runCatching { NovaFontPreset.valueOf(prefs.getString("font", NovaFontPreset.MODERN.name)!!) }.getOrDefault(NovaFontPreset.MODERN)
        set(v) = prefs.edit().putString("font", v.name).apply()
    var iconStyle: NovaIconStyle
        get() = runCatching { NovaIconStyle.valueOf(prefs.getString("icons", NovaIconStyle.ROUNDED.name)!!) }.getOrDefault(NovaIconStyle.ROUNDED)
        set(v) = prefs.edit().putString("icons", v.name).apply()
    var compactCards: Boolean
        get() = prefs.getBoolean("compact_cards", false)
        set(v) = prefs.edit().putBoolean("compact_cards", v).apply()
}
