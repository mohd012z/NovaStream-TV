package com.novastream.tv

/**
 * JVM-only tests for LiveStallWatchdog.
 */
fun main() {
    val wd = LiveStallWatchdog(quietWindowMs = 45_000)

    // 1. Advancing position -> never fires
    wd.reset()
    var now = 0L
    var pos = 0L
    var fired = false
    repeat(200) {
        now += 500; pos += 500
        fired = wd.onTick(now, pos)
    }
    check(!fired) { "advancing position must not fire" }

    // 2. Flat position for 45 s -> fires once, stays fired
    wd.reset()
    wd.onTick(0, 1000)
    var frozenFired = false
    for (t in 500L..60_000L step 500L) {
        frozenFired = wd.onTick(t, 1000)
        if (frozenFired) break
    }
    check(frozenFired) { "flat position 45s must fire" }
    check(wd.onTick(60_500, 1000)) { "stays fired after firing" }

    // 3. Short pause (under window) then progress -> no fire
    wd.reset()
    wd.onTick(0, 1000)
    for (t in 500L..30_000L step 500L) check(!wd.onTick(t, 1000)) { "30s pause must not fire" }
    check(!wd.onTick(30_500, 6000)) { "progress clears" }

    // 4. Reset re-arms (recovery reload path)
    wd.reset()
    check(!wd.onTick(0, 0)) { "reset re-armed, not fired" }

    println("LiveStallWatchdog tests passed")
}
