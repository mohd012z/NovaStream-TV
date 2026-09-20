package com.novastream.tv

import android.content.Context

/**
 * Persistent browse/search position memory.
 * Stores only UI state so returning from playback restores the user's place.
 */
class BrowseStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nova_browse_state_v1", Context.MODE_PRIVATE)

    data class Position(val index: Int = 0, val offset: Int = 0)

    fun position(key: String): Position = Position(
        index = prefs.getInt("${key}_index", 0).coerceAtLeast(0),
        offset = prefs.getInt("${key}_offset", 0)
    )

    fun savePosition(key: String, index: Int, offset: Int) {
        prefs.edit()
            .putInt("${key}_index", index.coerceAtLeast(0))
            .putInt("${key}_offset", offset)
            .apply()
    }

    var searchQuery: String
        get() = prefs.getString("search_query", "").orEmpty()
        set(value) { prefs.edit().putString("search_query", value).apply() }

    fun filter(name: String, default: String = "All"): String =
        prefs.getString("search_filter_$name", default) ?: default

    fun saveFilter(name: String, value: String) {
        prefs.edit().putString("search_filter_$name", value).apply()
    }
}
