package com.novastream.tv

/**
 * Live HLS stall watchdog (Anam bug report, 2026-10-01: "plays a few seconds,
 * then freezes").
 *
 * Root cause verified in the field (ptv2026 VIP feeds): the origin keeps
 * serving a FROZEN media playlist — the same segment window, an unchanged
 * EXT-X-MEDIA-SEQUENCE, and a stale EXT-X-PROGRAM-DATE-TIME (observed 9 days
 * old). Media3 then buffers the window, plays it out, and parks in a "live"
 * READY position with nothing new to load: the UI looks ready, the picture
 * is frozen.
 *
 * Signal (evidence-driven, no frame-drop heuristics):
 *   LIVE HLS + playback position makes no real progress
 *   + the HLS media sequence never advanced during that window
 *   => the playlist is stale, not the device.
 *
 * A healthy live feed's media sequence advances every ~2-10 s, so a quiet
 * window of [quietWindowMs] with zero sequence progress on a LIVE stream is a
 * reliable stale-feed signature. VOD (null sequence) never arms the watchdog.
 *
 * Pure Kotlin — JVM-testable (tdd/LiveStallWatchdogTest.kt).
 */
class LiveStallWatchdog(
    val quietWindowMs: Long = 45_000,   // 45 s frozen position + no seq advance => stale feed
    val minProgressMs: Long = 500L      // less than 0.5 s of real progress counts as "no progress"
) {
    private var lastPositionMs: Long = -1L
    private var stallSinceMs: Long = -1L
    private var fired = false

    /** Feed every player tick (position + the current HLS media sequence). */
    fun onTick(nowMs: Long, positionMs: Long, mediaSequence: Long?): Boolean {
        if (mediaSequence == null) {
            reset()
            return false // not an HLS live feed — disarmed
        }
        if (fired) return true
        if (lastPositionMs < 0) {
            lastPositionMs = positionMs
            stallSinceMs = nowMs
            return false
        }
        val progress = positionMs - lastPositionMs
        lastPositionMs = positionMs
        if (progress >= minProgressMs) {
            // Real progress: the feed is still delivering this window. When the
            // frozen window runs out, position stops and the quiet window fires.
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

    /** Called when the timeline's HLS media sequence advances — the playlist
     *  is provably alive; clear stall accumulation. */
    fun onSequenceAdvanced() {
        stallSinceMs = -1L
        fired = false
    }

    /** Fresh media item selected — re-arm. */
    fun reset() {
        lastPositionMs = -1L
        stallSinceMs = -1L
        fired = false
    }
}
