package com.novastream.tv

/** Lightweight JVM regression test for playlist normalization/filtering. */
fun main() {
    val raw = """#EXTM3U
#EXTINF:-1 group-title="News",Good
https://example.com/live/good.m3u8
#EXTINF:-1 group-title="News",Duplicate
https://example.com/live/good.m3u8
#EXTINF:-1 group-title="Movies",Bad scheme
ftp://example.com/movie.m3u8
#EXTINF:-1 group-title="Movies",Blank

""".trimIndent()

    val result = SmartPlaylistFilter.normalize(raw)
    check(result.contains("https://example.com/live/good.m3u8"))
    check(result.split("https://example.com/live/good.m3u8").size - 1 == 1)
    check(!result.contains("ftp://"))
    check(result.startsWith("#EXTM3U"))
    println("Smart playlist filter tests passed")
}
