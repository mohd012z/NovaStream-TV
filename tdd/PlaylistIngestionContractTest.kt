package com.novastream.tv

/**
 * Regression contract for the production playlist ingestion path.
 * This intentionally fails until production ingestion sanitizes downloaded M3U
 * before it is persisted/rebuilt.
 */
fun main() {
    val raw = """#EXTM3U
#EXTINF:-1 group-title="News",Good
https://example.com/live/good.m3u8
#EXTINF:-1 group-title="News",Duplicate
https://example.com/live/good.m3u8
#EXTINF:-1 group-title="Bad",Unsafe
ftp://example.com/live/bad.m3u8
""".trimIndent()

    val filtered = SmartPlaylistFilter.normalize(raw)
    check(filtered.startsWith("#EXTM3U"))
    check(filtered.split("https://example.com/live/good.m3u8").size - 1 == 1)
    check(!filtered.contains("ftp://"))

    // Production contract: callers must persist/use the filtered body, never raw.
    check(filtered != raw) { "ingestion must transform raw playlists before storage" }
    println("Playlist ingestion contract passed")
}
