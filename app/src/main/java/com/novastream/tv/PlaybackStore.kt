package com.novastream.tv

import android.content.Context

data class PlaybackRecord(
    val id: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_playback", Context.MODE_PRIVATE)

    fun save(record: PlaybackRecord) {
        prefs.edit()
            .putString("${record.id}.title", record.title)
            .putLong("${record.id}.position", record.positionMs)
            .putLong("${record.id}.duration", record.durationMs)
            .putLong("${record.id}.updated", record.updatedAt)
            .apply()
    }

    fun get(id: String): PlaybackRecord? {
        val title = prefs.getString("$id.title", null) ?: return null
        return PlaybackRecord(
            id = id,
            title = title,
            positionMs = prefs.getLong("$id.position", 0L),
            durationMs = prefs.getLong("$id.duration", 0L),
            updatedAt = prefs.getLong("$id.updated", 0L)
        )
    }

    fun recent(limit: Int = 12): List<PlaybackRecord> {
        val ids = prefs.all.keys.mapNotNull { key -> key.substringBefore(".").takeIf { key.endsWith(".title") } }.distinct()
        return ids.mapNotNull(::get).sortedByDescending { it.updatedAt }.take(limit)
    }
}
