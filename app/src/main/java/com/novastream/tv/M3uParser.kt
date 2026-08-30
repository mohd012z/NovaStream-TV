package com.novastream.tv

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
                line.startsWith("#") -> Unit
                info != null -> {
                    val ext = info!!
                    val attrs = attrRegex.findAll(ext).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = ext.substringAfterLast(',', attrs["tvg-name"] ?: "Channel").trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                    val group = attrs["group-title"]
                    val kind = classify(group, line)
                    result += PlaylistItem(
                        id = stableId(line, attrs["tvg-id"] ?: name),
                        name = name,
                        streamUrl = line,
                        tvgId = attrs["tvg-id"],
                        tvgName = attrs["tvg-name"],
                        logoUrl = attrs["tvg-logo"],
                        groupTitle = group,
                        userAgent = pendingUserAgent,
                        referer = pendingReferer,
                        kind = kind
                    )
                    info = null
                    pendingUserAgent = null
                    pendingReferer = null
                }
            }
        }
        return result
    }

    private fun classify(group: String?, url: String): MediaKind {
        val g = group.orEmpty().lowercase()
        val u = url.lowercase()
        return when {
            "series" in g || "/series/" in u -> MediaKind.SERIES
            "movie" in g || "vod" in g || "/movie/" in u || u.endsWith(".mp4") || u.endsWith(".mkv") -> MediaKind.MOVIE
            "live" in g || u.endsWith(".m3u8") || u.contains("/live/") -> MediaKind.LIVE
            else -> MediaKind.UNKNOWN
        }
    }

    private fun stableId(url: String, seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest("$seed|$url".toByteArray())
        return bytes.take(10).joinToString("") { "%02x".format(it) }
    }
}
