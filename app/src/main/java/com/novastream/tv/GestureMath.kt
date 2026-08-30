package com.novastream.tv

import kotlin.math.roundToInt

object GestureMath {
    fun brightness(start: Float, normalizedDelta: Float, sensitivity: Float): Float =
        (start + normalizedDelta * sensitivity).coerceIn(0.05f, 1f)

    fun volume(start: Int, max: Int, normalizedDelta: Float, sensitivity: Float): Int {
        if (max <= 0) return 0
        val delta = (normalizedDelta * max * sensitivity).roundToInt()
        return (start + delta).coerceIn(0, max)
    }
}
