package com.novastream.tv

fun main() {
    check(GestureMath.brightness(0.5f, 0.5f, 0.30f) == 0.65f)
    check(GestureMath.brightness(0.95f, 1f, 0.30f) == 1f)
    check(GestureMath.volume(5, 10, 0.5f, 0.60f) == 8)
    check(GestureMath.volume(9, 10, 1f, 0.60f) == 10)

    val p1 = EpgProgramme("tv1", "Morning", startMs = 1000, stopMs = 2000)
    val p2 = EpgProgramme("tv2", "News", startMs = 1000, stopMs = 3000)
    val index = EpgIndex(listOf(p1, p2))
    check(index.now("tv1", 1500)?.title == "Morning")
    check(index.now("tv1", 2500) == null)
    check(index.progress(p2, 2000) == 0.5f)

    println("V2 core tests passed")
}
