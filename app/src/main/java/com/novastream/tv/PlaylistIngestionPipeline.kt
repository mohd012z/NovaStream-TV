package com.novastream.tv

/**
 * Single production gateway for downloaded playlists.
 * Raw network bodies must never be persisted directly.
 */
object PlaylistIngestionPipeline {
    data class Result(
        val body: String,
        val itemCount: Int,
        val rejectedCount: Int,
        val message: String
    )

    fun sanitize(raw: String): Result {
        val rawCount = runCatching { M3uParser.parse(raw).size }.getOrDefault(0)
        val body = SmartPlaylistFilter.normalize(raw)
        val cleanCount = runCatching { M3uParser.parse(body).size }.getOrDefault(0)
        val rejected = (rawCount - cleanCount).coerceAtLeast(0)
        val message = when {
            cleanCount == 0 -> "No safe playable entries"
            rejected > 0 -> "Ready • $cleanCount kept • $rejected filtered"
            else -> "Ready • $cleanCount entries"
        }
        return Result(body, cleanCount, rejected, message)
    }
}
