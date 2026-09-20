package com.novastream.tv

import java.net.URLDecoder
import java.security.MessageDigest

object M3uParser {
    private val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(raw: String): List<PlaylistItem> {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val result = mutableListOf<PlaylistItem>()
        var info: String? = null
        var pendingUserAgent: String? = null
        var pendingReferer: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> pendingUserAgent = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) || line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) -> pendingReferer = line.substringAfter('=')
                line.startsWith("http-user-agent=", ignoreCase = true) -> pendingUserAgent = line.substringAfter('=')
                line.startsWith("http-referrer=", ignoreCase = true) || line.startsWith("http-referer=", ignoreCase = true) -> pendingReferer = line.substringAfter('=')
                line.startsWith("#") -> Unit
                info != null -> {
                    val ext = info
                    val attrs = attrRegex.findAll(ext).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = ext.substringAfterLast(',', attrs["tvg-name"] ?: "Channel").trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                    val group = attrs["group-title"]
                    val parsedUrl = parseUrlAndHeaders(line)
                    val kind = classify(group, parsedUrl.url)
                    val seriesInfo = if (kind == MediaKind.SERIES || kind == MediaKind.SHORT_DRAMA) parseSeriesInfo(name) else null
                    result += PlaylistItem(
                        id = stableId(parsedUrl.url, attrs["tvg-id"] ?: name),
                        name = name,
                        streamUrl = parsedUrl.url,
                        tvgId = attrs["tvg-id"],
                        tvgName = attrs["tvg-name"],
                        logoUrl = attrs["tvg-logo"],
                        groupTitle = group,
                        userAgent = parsedUrl.userAgent ?: pendingUserAgent,
                        referer = parsedUrl.referer ?: pendingReferer,
                        country = attrs["tvg-country"]?.takeIf { it.isNotBlank() },
                        kind = kind,
                        showName = seriesInfo?.first,
                        episodeNumber = seriesInfo?.second
                    )
                    info = null
                    pendingUserAgent = null
                    pendingReferer = null
                }
            }
        }
        return result
    }

    private data class ParsedUrl(val url: String, val userAgent: String?, val referer: String?)

    private fun parseUrlAndHeaders(raw: String): ParsedUrl {
        val url = raw.substringBefore('|').trim()
        if (!raw.contains('|')) return ParsedUrl(url, null, null)
        val headerPart = raw.substringAfter('|')
        val pairs = headerPart.split('&').mapNotNull { token ->
            val idx = token.indexOf('=')
            if (idx <= 0) null else token.substring(0, idx).trim().lowercase() to decode(token.substring(idx + 1).trim())
        }.toMap()
        return ParsedUrl(
            url = url,
            userAgent = pairs["user-agent"] ?: pairs["user_agent"],
            referer = pairs["referer"] ?: pairs["referrer"]
        )
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun classify(group: String?, url: String): MediaKind {
        val g = group.orEmpty().lowercase()
        val u = url.lowercase()
        return when {
            "series" in g || "/series/" in u -> MediaKind.SERIES
            "short drama" in g || "drama short" in g || "mini drama" in g || "microdrama" in g || "micro drama" in g -> MediaKind.SHORT_DRAMA
            "music video" in g || g.contains("mv") && "music" in g -> MediaKind.MUSIC_VIDEO
            "music" in g || "radio" in g -> MediaKind.MUSIC
            "movie" in g || "vod" in g || "/movie/" in u || u.endsWith(".mp4") || u.endsWith(".mkv") -> MediaKind.MOVIE
            "live" in g || u.endsWith(".m3u8") || u.contains("/live/") -> MediaKind.LIVE
            else -> MediaKind.UNKNOWN
        }
    }

    // Recognizes common episode-suffix patterns on a title such as:
    // "Bulan Henti Bicara Ep01", "Bulan Henti Bicara Ep 01", "Show - Episode 12",
    // "Show E07", "Show S01E07", "Show - Ep.1", "Show 12".
    // Returns the cleaned show name (falling back to the original title when
    // nothing matches) and the parsed episode number (null when not found).
    private val episodePatterns = listOf(
        Regex("""^(.*?)[\s._-]+[Ss]\d{1,2}[\s._-]*[Ee][Pp]?\.?\s*(\d{1,4})\s*$"""),
        Regex("""^(.*?)[\s._-]+[Ee]pisode\.?\s*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(.*?)[\s._-]+[Ee]p\.?\s*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(.*?)[\s._-]+[Ee](\d{1,4})\s*$"""),
        Regex("""^(.*?)[\s._-]+\(?(\d{1,4})\)?\s*$""")
    )

    fun parseSeriesInfo(title: String): Pair<String, Int?> {
        for (pattern in episodePatterns) {
            val match = pattern.find(title) ?: continue
            val show = match.groupValues[1].trim().trim('-', '.', ':', '_').trim()
            val episode = match.groupValues[2].toIntOrNull()
            if (show.isNotBlank() && episode != null) return show to episode
        }
        return title to null
    }

    private fun stableId(url: String, seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest("$seed|$url".toByteArray())
        return bytes.take(10).joinToString("") { "%02x".format(it) }
    }
}
