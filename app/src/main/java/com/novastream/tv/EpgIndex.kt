package com.novastream.tv

class EpgIndex(programmes: List<EpgProgramme>) {
    private val byChannel = programmes.groupBy { it.channelId }

    fun now(channelId: String?, nowMs: Long = System.currentTimeMillis()): EpgProgramme? {
        if (channelId.isNullOrBlank()) return null
        return byChannel[channelId]?.firstOrNull { nowMs in it.startMs until it.stopMs }
    }

    fun progress(programme: EpgProgramme, nowMs: Long = System.currentTimeMillis()): Float {
        val duration = programme.stopMs - programme.startMs
        if (duration <= 0) return 0f
        return ((nowMs - programme.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
}
