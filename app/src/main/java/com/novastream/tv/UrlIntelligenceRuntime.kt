package com.novastream.tv

/**
 * Process-wide runtime observation hub for the URL Intelligence engine.
 *
 * The engine itself is a pure, JVM-testable core; this is the thin
 * Android-side glue that feeds it REAL evidence while the app runs:
 *
 *  - a channel the user actually played  -> `runtimeObserved = true`
 *  - a watchdog-stale-feed event         -> behavior + activity memory
 *
 * "String presence is a fact, program use is an inference" — a URL only
 * becomes runtime-observed here, when the player genuinely used it.
 */
object UrlIntelligenceRuntime {
    val engine: UrlIntelligence by lazy { UrlIntelligence().apply { nowMs = System.currentTimeMillis() } }

    /** Mark a stream URL as runtime-observed (played by Media3). */
    fun markPlayed(streamUrl: String, latencyMs: Int = 0) {
        engine.nowMs = System.currentTimeMillis()
        engine.register(
            streamUrl,
            source = DiscoverySurface.M3U_ATTR,
            runtimeObserved = true,
            trigger = "CHANNEL_SELECT",
            outputConsumer = "Media3",
            caller = "PlayerScreen"
        )
        engine.observe(streamUrl, listOf(streamUrl), latencyMs, success = true)
    }

    /** Feed a watchdog-stale event (playlist frozen) into engine + memory. */
    fun markStaleFeed(streamUrl: String, repo: MemoryRepository?) {
        engine.nowMs = System.currentTimeMillis()
        engine.register(
            streamUrl,
            source = DiscoverySurface.M3U_ATTR,
            runtimeObserved = true,
            trigger = "CHANNEL_SELECT",
            outputConsumer = "Media3"
        )
        repo?.observe(
            NovaEvent(
                kind = "URL_STALE",
                targetId = streamUrl,
                detail = "HLS feed not advancing; watchdog reload triggered",
                type = MemoryType.HEALTH_METRIC
            )
        )
    }
}
