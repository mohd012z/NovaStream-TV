package com.novastream.tv

/**
 * Live stall watchdog (Anam bug report, 2026-10-01: "plays a few seconds,
 * then freezes").
 *
 * Root cause verified in the field (ptv2026 VIP feeds): the origin serves a
 * FROZEN media playlist — the same segment window, an unchanged
 * EXT-X-MEDIA-SEQUENCE, and a 9-day-old EXT-X-PROGRAM-DATE-TIME. Media3
 * buffers the window, plays it out, then parks with nothing new to load:
 * the UI looks ready, the picture is frozen. The existing `silentStall`
 * detector (READY + !isPlaying) does not catch this: during the play-out the
 * player still reports isPlaying=true, and after the window runs out it can
 * sit in READY without ever flipping to !isPlaying.
 *
 * Signal (deliberately simple and robust): on a LIVE item, the playback
 * position makes no real progress for [quietWindowMs]. Live feeds advance
 * position continuously (even across the live-edge jump-to-now), so a 45 s
 * flat position on live is a stale-freeze signature. VOD items are never
 * armed — the caller gates on the live kind.
 *
 * Pure Kotlin — JVM-testable (tdd/LiveStallWatchdogTest.kt).
 */
class LiveStallWatchdog(
    val quietWindowMs: Long = 45_000,   // 45 s of flat position => frozen feed
    val minProgressMs: Long = 500L      // less than 0.5 s of real progress counts as "no progress"
) {
    private var lastPositionMs: Long = -1L
    private var stallSinceMs: Long = -1L
    private var fired = false

    /** Feed every player tick while a LIVE item is selected. */
    fun onTick(nowMs: Long, positionMs: Long): Boolean {
        if (fired) return true
        if (lastPositionMs < 0) {
            lastPositionMs = positionMs
            stallSinceMs = nowMs
            return false
        }
        val progress = positionMs - lastPositionMs
        lastPositionMs = positionMs
        if (progress >= minProgressMs) {
            stallSinceMs = -1L
            return false
        }
        if (stallSinceMs < 0) stallSinceMs = nowMs
        if (nowMs - stallSinceMs >= quietWindowMs) {
            fired = true
            return true
        }
        return false
    }

    /** Fresh media item selected / recovery reload — re-arm. */
    fun reset() {
        lastPositionMs = -1L
        stallSinceMs = -1L
        fired = false
    }
}
