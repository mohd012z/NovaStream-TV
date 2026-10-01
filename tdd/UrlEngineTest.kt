package com.novastream.tv

/**
 * JVM-only tests for the URL Intelligence Engine.
 * Run: kotlinc app/src/main/java/com/novastream/tv/UrlIntelligenceEngine.kt tdd/UrlEngineTest.kt -d /tmp/url-core
 *      java -cp "/tmp/url-core:toolchain/kotlinc/lib/kotlin-stdlib.jar" com.novastream.tv.UrlEngineTestKt
 */
fun main() {
    val e = UrlIntelligence()
    e.nowMs = 1_000_000

    // 1. Discovery across surfaces (only URLs, noise filtered)
    val dexRaw = """
        ...binary... https://depanptv.com/config/Perfecttv/allowed_domains.json
        https://depanptv.com/config/Perfecttv/device_ping.php
        https://ptv2026.com
        0x243970 not a url
    """.trimIndent()
    val found = e.discover(DiscoverySurface.DEX, dexRaw)
    check(found.size == 3) { "expected 3 urls got ${found.size}: $found" }

    // 2. Classification — never same subsystem for .m3u8 vs config.json
    check(UrlIntelligence.classify("https://cdn.example/hls/index.m3u8") == UrlRole.STREAM)
    check(UrlIntelligence.classify("https://host/playlist.m3u") == UrlRole.PLAYLIST)
    check(UrlIntelligence.classify("https://epg.example/epg.xml") == UrlRole.EPG)
    check(UrlIntelligence.classify("https://config.example/v1/config.json") == UrlRole.REMOTE_CONFIG)
    check(UrlIntelligence.classify("https://device.example/ping.php") == UrlRole.DEVICE_HEALTH)
    check(UrlIntelligence.classify("wss://ws.example/live") == UrlRole.WEBSOCKET)
    check(UrlIntelligence.classify("https://api.example/v1/channels") == UrlRole.API)
    check(UrlIntelligence.classify("https://auth.example/login?token=abc") == UrlRole.AUTH)
    check(UrlIntelligence.classify("https://a.example/img.png") == UrlRole.IMAGE)
    check(UrlIntelligence.classify("https://a.example/vtt") == UrlRole.SUBTITLE)
    check(UrlIntelligence.classify("https://x.example/unknown") == UrlRole.UNKNOWN)

    // 3. Provenance: string presence = fact, use = inference
    val rec = e.register(
        "https://config.example/v1/config.json",
        role = UrlRole.REMOTE_CONFIG,
        source = DiscoverySurface.DEX,
        location = Location("PerfectTV_v6.apk.bak", "classes.dex", byteOffsetHex = "0x243970"),
        caller = null, trigger = null,
        staticFound = true, runtimeObserved = false, xrefResolved = false,
        endpointId = "ep_config"
    )
    check(rec.evidence.kind == EvidenceKind.STATIC_ONLY) { "static-only evidence: ${rec.evidence.kind}" }
    check(rec.id == "ep_config")
    check(rec.host == "config.example")
    check(rec.protocol == "HTTPS")
    check(rec.locations.single().hierarchy().last() == "xref pending")

    // 4. URL construction: base+path+query recognized, not just full literal
    val c = UrlConstruction.construct("https://api.example", "/channels", mapOf("region" to "MY"), token = true)
    check(c.build() == "https://api.example/channels?region=MY") { "build=${c.build()}" }
    check(e.register("https://api.example/channels?region=MY", construction = c, source = DiscoverySurface.APP_CODE, runtimeObserved = true).construction != null)

    // 5. Redirect chain original -> hop1 -> hop2 -> final
    val chain = listOf("http://a.com", "https://b.com", "https://b.com/real.m3u8")
    val r = e.observe("http://a.com", chain, latencyMs = 83, success = true)
    check(r.redirectChain == chain) { "chain=${r.redirectChain}" }
    check(r.evidence.kind == EvidenceKind.RUNTIME_ONLY)
    check(r.behavior?.successfulChecks == 1)
    check(r.lastDiff != null) { "diff recorded on first observe" }

    // 6. Change detection — same endpoint, v1 -> v2 path change
    e.observe("https://config.example/v1/config.json", listOf("https://config.example/v1/config.json"), 90, true, endpointId = "ep_config")
    e.observe("https://config.example/v2/config.json", listOf("https://config.example/v2/config.json"), 90, true, endpointId = "ep_config")
    val d = e.diff("https://config.example/v2/config.json")
    check(d != null && !d.hostChanged && d.pathChanged) { "v1->v2 path change: $d" }
    check(d?.previous?.path?.contains("/v1/") == true && d?.current?.path?.contains("/v2/") == true)

    // 7. Response schema: type change detected, no values stored
    val schema = ResponseSchema(UrlRole.REMOTE_CONFIG, mapOf("allowedDomains" to "array<string>", "playlistUrl" to "string", "refreshInterval" to "integer"))
    e.observe("https://config.example/v1/config.json", listOf("https://config.example/v1/config.json"), 91, true, schema, endpointId = "ep_config")
    val mismatches = UrlIntelligence.schemaMismatches(schema.shape, mapOf("allowedDomains" to "object", "playlistUrl" to "string", "refreshInterval" to "integer"))
    check(mismatches.size == 1 && mismatches[0].startsWith("allowedDomains")) { "schema mismatch: $mismatches" }

    // 8. Behavior stats accumulate (observations across steps 6,7,8 for ep_config)
    e.observe("https://config.example/v1/config.json", listOf("https://config.example/v1/config.json"), 120, true, endpointId = "ep_config")
    e.observe("https://config.example/v1/config.json", listOf("https://config.example/v1/config.json"), 160, true, endpointId = "ep_config")
    val beh = e.records["ep_config"]?.behavior
    check(beh != null && beh.observations >= 5) { "obs=${beh?.observations}" }
    check(beh?.minLatencyMs == 90) { "min=${beh?.minLatencyMs}" }
    check(beh?.maxLatencyMs == 160) { "max=${beh?.maxLatencyMs}" }
    check(beh?.typicalLatencyRange == "90–160 ms") { "range=${beh?.typicalLatencyRange}" }
    check(beh?.successfulChecks == beh?.observations) { "all success" }

    // 9. Sanitize — never a credential collector
    check(UrlIntelligence.sanitizeUrl("https://h/api?token=SECRET&x=1") == "https://h/api?token=•••&x=1")
    check(UrlIntelligence.sanitizeUrl("https://h/api?apikey=K&region=MY") == "https://h/api?apikey=•••&region=MY")
    check(UrlIntelligence.sanitizeUrl("https://h/plain") == "https://h/plain")
    check(UrlIntelligence.sanitizeHeader("Authorization", "Bearer xyz") == "•••")
    check(rec.sanitizedCopy() == "https://config.example/v1/config.json") // no creds

    // 10. Dependency graph + source-level root cause
    val cfg = e.filter(UrlRole.REMOTE_CONFIG).first()
    val pl = e.register("https://playlist.example/free.m3u", source = DiscoverySurface.DEX, runtimeObserved = true, endpointId = "ep_playlist")
    e.addEdge(cfg.id, pl.id, RelationType.CONFIG_PROVIDES_PLAYLIST)
    check(e.dependents(cfg.id).any { it.id == pl.id })
    check(e.rootCause(emptyMap(), setOf("pl1")) == "NO_CHANNELS")
    check(e.rootCause(mapOf("ch1" to "pl1", "ch2" to "pl1"), setOf("pl1")) == "SOURCE-LEVEL FAILURE")
    check(e.rootCause(mapOf("ch1" to "pl1", "ch2" to "pl2"), setOf("pl1", "pl2")) == "MULTI-SOURCE FAILURE")
    check(e.rootCause(mapOf("ch1" to "pl1", "ch2" to "pl2"), setOf("pl1")) == "PARTIAL SOURCE FAILURE")
    check(e.rootCause(mapOf("ch1" to "pl1", "ch2" to "pl2"), emptySet()) == "STREAM-LEVEL")

    // 11. Summary counts
    val s = e.summary()
    check(s["DISCOVERED"]!! >= 3) { "summary=$s" }
    check(s["RUNTIME"]!! >= 1) { "runtime seen: $s" }

    // 12. Copy payload
    check(rec.copy("HOST") == "config.example")
    check(rec.copy("METHOD") == "GET")
    check(rec.copy("JSON").contains("\"role\":\"REMOTE_CONFIG\""))

    println("URL Intelligence engine tests passed")
}
