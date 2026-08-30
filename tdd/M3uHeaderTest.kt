package com.novastream.tv

fun main() {
    val raw = """
        #EXTM3U
        #EXTINF:-1 tvg-id="tv1" tvg-logo="https://img/logo.png" group-title="MALAYSIA",TV1
        https://example.com/live.m3u8|User-Agent=PerfectTV&Referer=https%3A%2F%2Fexample.com%2F
    """.trimIndent()
    val item = M3uParser.parse(raw).single()
    check(item.streamUrl == "https://example.com/live.m3u8")
    check(item.userAgent == "PerfectTV")
    check(item.referer == "https://example.com/")
    check(item.logoUrl == "https://img/logo.png")
    println("M3U header test passed")
}
