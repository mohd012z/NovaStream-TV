package com.novastream.tv

/**
 * JVM-only tests for LiveStallWatchdog.
 */
fun main() {
    val wd = LiveStallWatchdog(quietWindowMs = 45_000)

    // 1. VOD (null sequence) never fires
    check(!wd.onTick(0, 1000, null)) { "null seq disarmed" }
    check(!wd.onTick(120_000, 1000, null)) { "VOD frozen still disarmed" }

    // 2. Live HLS, advancing position, frozen sequence -> no fire
    wd.reset()
    var now = 0L
    var pos = 0L
    var fired = false
    repeat(200) {
        now += 500; pos += 500
        fired = wd.onTick(now, pos, 42L)
    }
    check(!fired) { "advancing position must not fire" }

    // 3. Live HLS, frozen position, frozen sequence for 45 s -> fires once
    wd.reset()
    wd.onTick(0, 1000, 42L)
    var frozenFired = false
    for (t in 500L..60_000L step 500L) {
        frozenFired = wd.onTick(t, 1000, 42L)
        if (frozenFired) break
    }
    check(frozenFired) { "frozen position 45s must fire" }
    check(wd.onTick(60_500, 1000, 42L)) { "stays fired after firing" }

    // 4. Sequence advance clears the stall accumulation
    wd.reset()
    wd.onTick(0, 1000, 42L)
    for (t in 500L..30_000L step 500L) wd.onTick(t, 1000, 42L)
    wd.onSequenceAdvanced()
    var after = false
    for (t in 30_500L..60_000L step 500L) after = wd.onTick(t, 1000, 42L)
    check(!after) { "sequence advance must clear stall" }

    // 5. Reset re-arms
    wd.reset()
    check(!wd.onTick(0, 0, null)) { "reset disarmed" }

    println("LiveStallWatchdog tests passed")
}
