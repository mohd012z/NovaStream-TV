package com.novastream.tv

enum class PlaybackContext {
    SHORT, SERIES, MOVIE, LIVE, MUSIC, MUSIC_VIDEO, UNKNOWN
}

enum class PlaybackEndAction {
    NEXT_EPISODE,
    NEXT_QUEUE_ITEM,
    SERIES_COMPLETE,
    MOVIE_COMPLETE,
    KEEP_LIVE,
    SHOW_WHATS_NEXT
}

data class PlaybackDecision(
    val action: PlaybackEndAction,
    val reason: String
)

object UniversalPlaybackPolicy {
    fun contextFor(kind: MediaKind): PlaybackContext = when (kind) {
        MediaKind.SHORT_DRAMA -> PlaybackContext.SHORT
        MediaKind.SERIES -> PlaybackContext.SERIES
        MediaKind.MOVIE -> PlaybackContext.MOVIE
        MediaKind.LIVE -> PlaybackContext.LIVE
        MediaKind.MUSIC -> PlaybackContext.MUSIC
        MediaKind.MUSIC_VIDEO -> PlaybackContext.MUSIC_VIDEO
        MediaKind.UNKNOWN -> PlaybackContext.UNKNOWN
    }

    fun onEnded(
        item: PlaylistItem,
        hasNextEpisode: Boolean,
        hasNextQueueItem: Boolean
    ): PlaybackDecision = when (item.kind) {
        MediaKind.SHORT_DRAMA, MediaKind.SERIES -> {
            if (hasNextEpisode) {
                PlaybackDecision(PlaybackEndAction.NEXT_EPISODE, "Continue the same series")
            } else {
                PlaybackDecision(PlaybackEndAction.SERIES_COMPLETE, "Series completed")
            }
        }
        MediaKind.MOVIE ->
            PlaybackDecision(PlaybackEndAction.MOVIE_COMPLETE, "Movie completed")
        MediaKind.LIVE ->
            PlaybackDecision(PlaybackEndAction.KEEP_LIVE, "Live streams should recover instead of being treated as completed")
        MediaKind.MUSIC, MediaKind.MUSIC_VIDEO -> {
            if (hasNextQueueItem) {
                PlaybackDecision(PlaybackEndAction.NEXT_QUEUE_ITEM, "Continue playback queue")
            } else {
                PlaybackDecision(PlaybackEndAction.SHOW_WHATS_NEXT, "Playback queue completed")
            }
        }
        MediaKind.UNKNOWN ->
            PlaybackDecision(PlaybackEndAction.SHOW_WHATS_NEXT, "Playback completed")
    }

    fun isSameSeries(current: PlaylistItem, next: PlaylistItem?): Boolean {
        if (next == null) return false
        val currentShow = current.showName?.trim()?.lowercase()
        val nextShow = next.showName?.trim()?.lowercase()
        return currentShow != null && currentShow.isNotBlank() && currentShow == nextShow
    }
}

enum class PlaybackHealth {
    CONNECTING, BUFFERING, READY, PLAYING, ENDED, RECOVERING, ERROR
}

enum class PlaybackTraceStage {
    SOURCE, NETWORK, MANIFEST, BUFFER, DECODER, RENDER, RECOVERY
}

data class PlaybackTraceSnapshot(
    val health: PlaybackHealth = PlaybackHealth.CONNECTING,
    val sourceHost: String = "",
    val protocol: String = "AUTO",
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val retryCount: Int = 0,
    val droppedFrames: Int = 0,
    val bandwidthEstimateBps: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoBitrate: Int = 0,
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val lastError: String? = null
) {
    val bufferedAheadMs: Long
        get() = (bufferedPositionMs - positionMs).coerceAtLeast(0L)

    val stage: PlaybackTraceStage
        get() = when {
            health == PlaybackHealth.ERROR -> PlaybackTraceStage.RECOVERY
            health == PlaybackHealth.RECOVERING -> PlaybackTraceStage.RECOVERY
            health == PlaybackHealth.BUFFERING && bufferedAheadMs <= 500L -> PlaybackTraceStage.BUFFER
            health == PlaybackHealth.CONNECTING -> PlaybackTraceStage.NETWORK
            else -> PlaybackTraceStage.RENDER
        }

    val summary: String
        get() = buildString {
            append(health.name)
            append(" • ")
            append(protocol)
            append(" • buffer ")
            append(bufferedAheadMs / 1000f)
            append("s")
            if (retryCount > 0) append(" • recovery ").append(retryCount)
            if (bandwidthEstimateBps > 0) append(" • net ").append(bandwidthEstimateBps / 1_000_000f).append("Mbps")
            if (videoHeight > 0) append(" • ").append(videoHeight).append("p")
            if (droppedFrames > 0) append(" • dropped ").append(droppedFrames)
        }
}

data class PlaybackDiagnostics(
    val health: PlaybackHealth = PlaybackHealth.CONNECTING,
    val retryCount: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastError: String? = null
)
