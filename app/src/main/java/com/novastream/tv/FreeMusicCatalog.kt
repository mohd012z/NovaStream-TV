package com.novastream.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Public/free music catalog provider for NovaStream.
 *
 * Audius is the preferred zero-config provider. Jamendo is optional and only
 * activates when the user supplies their own client id.
 */
object FreeMusicCatalog {
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val streamUrl: String,
        val provider: String,
        val durationSeconds: Int = 0
    )

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "NovaStreamerTV/2.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun audiusHost(): String {
        val raw = get("https://api.audius.co")
        val arr = JSONObject(raw).optJSONArray("data") ?: error("Audius discovery unavailable")
        return arr.optString(0).trimEnd('/').ifBlank { error("Audius host unavailable") }
    }

    fun audiusTrending(limit: Int = 40): List<Track> = runCatching {
        val host = audiusHost()
        val raw = get("$host/v1/tracks/trending?limit=${limit.coerceIn(1,100)}")
        val arr = JSONObject(raw).optJSONArray("data") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Track(
                id = "audius:$id",
                title = o.optString("title", "Untitled"),
                artist = o.optJSONObject("user")?.optString("name").orEmpty(),
                artworkUrl = o.optJSONObject("artwork")?.optString("480x480")?.takeIf { it.isNotBlank() },
                streamUrl = "$host/v1/tracks/$id/stream",
                provider = "Audius",
                durationSeconds = o.optInt("duration")
            )
        }
    }.getOrDefault(emptyList())

    fun audiusSearch(query: String, limit: Int = 40): List<Track> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        val host = audiusHost()
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = get("$host/v1/tracks/search?query=$q&limit=${limit.coerceIn(1,100)}")
        val arr = JSONObject(raw).optJSONArray("data") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Track(
                id = "audius:$id",
                title = o.optString("title", "Untitled"),
                artist = o.optJSONObject("user")?.optString("name").orEmpty(),
                artworkUrl = o.optJSONObject("artwork")?.optString("480x480")?.takeIf { it.isNotBlank() },
                streamUrl = "$host/v1/tracks/$id/stream",
                provider = "Audius",
                durationSeconds = o.optInt("duration")
            )
        }
    }.getOrDefault(emptyList())

    fun jamendo(clientId: String, query: String = "", limit: Int = 40): List<Track> = runCatching {
        if (clientId.isBlank()) return@runCatching emptyList()
        val search = if (query.isBlank()) "" else "&search=" + URLEncoder.encode(query, "UTF-8")
        val raw = get(
            "https://api.jamendo.com/v3.0/tracks/?client_id=" +
                URLEncoder.encode(clientId, "UTF-8") +
                "&format=json&limit=${limit.coerceIn(1,200)}&type=single+albumtrack" +
                "&order=popularity_week&audioformat=mp32&imagesize=300$search"
        )
        val arr = JSONObject(raw).optJSONArray("results") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val audio = o.optString("audio")
            if (audio.isBlank()) return@mapNotNull null
            Track(
                id = "jamendo:" + o.optString("id"),
                title = o.optString("name", "Untitled"),
                artist = o.optString("artist_name"),
                artworkUrl = o.optString("image").takeIf { it.isNotBlank() },
                streamUrl = audio,
                provider = "Jamendo",
                durationSeconds = o.optInt("duration")
            )
        }
    }.getOrDefault(emptyList())
}
