package com.novastream.tv

import android.net.Uri
import androidx.media3.common.MimeTypes

/**
 * Shared URL/media classifier for Nova playback engines.
 *
 * This does not bypass authentication, DRM, or protected services. It normalizes
 * ordinary public/authorized media links so Live, Movie, Drama, Shorts and Music
 * can share one playback policy layer.
 */
object NovaStreamLibrary {
    enum class Container { HLS, DASH, RTSP, SMOOTH_STREAMING, AUDIO, VIDEO_FILE, UNKNOWN }
    enum class PlaybackProfile { LIVE, CINEMA, EPISODE, SHORT, MUSIC, MUSIC_VIDEO, AUTO }

    data class StreamDescriptor(
        val originalUrl: String,
        val normalizedUrl: String,
        val container: Container,
        val mimeType: String?,
        val profile: PlaybackProfile,
        val isHttp: Boolean,
        val host: String,
        val safeForDirectPlayback: Boolean
    )

    fun describe(url: String, hint: MediaKind = MediaKind.UNKNOWN, groupTitle: String? = null): StreamDescriptor {
        val trimmed = url.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val host = uri?.host.orEmpty()
        val path = uri?.path?.lowercase().orEmpty()
        val container = when {
            scheme == "rtsp" || scheme == "rtsps" -> Container.RTSP
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> Container.HLS
            path.endsWith(".mpd") -> Container.DASH
            path.endsWith(".ism") || path.contains(".ism/manifest") -> Container.SMOOTH_STREAMING
            path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") ||
                path.endsWith(".flac") || path.endsWith(".ogg") || path.endsWith(".opus") -> Container.AUDIO
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
                path.endsWith(".mkv") || path.endsWith(".ts") -> Container.VIDEO_FILE
            else -> Container.UNKNOWN
        }
        val group = groupTitle.orEmpty().lowercase()
        val profile = when {
            container == Container.AUDIO || group.contains("music audio") || group.contains("radio") -> PlaybackProfile.MUSIC
            group.contains("music video") || group.contains("mv") || group.equals("music", true) -> PlaybackProfile.MUSIC_VIDEO
            group.contains("short drama") || group.contains("shorts") || group.contains("vertical drama") -> PlaybackProfile.SHORT
            hint == MediaKind.LIVE -> PlaybackProfile.LIVE
            hint == MediaKind.MOVIE -> PlaybackProfile.CINEMA
            hint == MediaKind.SERIES -> PlaybackProfile.EPISODE
            else -> PlaybackProfile.AUTO
        }
        val mime = when (container) {
            Container.HLS -> MimeTypes.APPLICATION_M3U8
            Container.DASH -> MimeTypes.APPLICATION_MPD
            Container.AUDIO -> when {
                path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
                path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
                path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
                path.endsWith(".ogg") || path.endsWith(".opus") -> MimeTypes.AUDIO_OGG
                else -> null
            }
            Container.VIDEO_FILE -> when {
                path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
                path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
                else -> null
            }
            else -> null
        }
        return StreamDescriptor(
            originalUrl = url,
            normalizedUrl = trimmed,
            container = container,
            mimeType = mime,
            profile = profile,
            isHttp = scheme == "http" || scheme == "https",
            host = host,
            safeForDirectPlayback = scheme in setOf("http", "https", "rtsp", "rtsps")
        )
    }

    /**
     * Filtering for discovered/imported URLs. Rejects non-network schemes and obvious
     * web pages; unknown HTTP URLs remain allowed because many IPTV/CDN endpoints do
     * not expose a file extension.
     */
    fun acceptDirectMediaUrl(url: String): Boolean {
        val d = describe(url)
        if (!d.safeForDirectPlayback || d.normalizedUrl.isBlank()) return false
        val path = runCatching { Uri.parse(d.normalizedUrl).path?.lowercase().orEmpty() }.getOrDefault("")
        return !(path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php"))
    }

    fun bufferPolicy(profile: PlaybackProfile): BufferPolicy = when (profile) {
        PlaybackProfile.LIVE -> BufferPolicy(8_000, 24_000, 500, 2_000, 3_000)
        PlaybackProfile.CINEMA -> BufferPolicy(20_000, 90_000, 750, 4_000, 15_000)
        PlaybackProfile.EPISODE -> BufferPolicy(15_000, 60_000, 750, 3_000, 15_000)
        PlaybackProfile.SHORT -> BufferPolicy(4_000, 20_000, 300, 1_000, 5_000)
        PlaybackProfile.MUSIC -> BufferPolicy(10_000, 60_000, 250, 1_500, 30_000)
        PlaybackProfile.MUSIC_VIDEO -> BufferPolicy(10_000, 45_000, 500, 2_000, 10_000)
        PlaybackProfile.AUTO -> BufferPolicy(8_000, 45_000, 500, 2_000, 10_000)
    }

    data class BufferPolicy(
        val minMs: Int,
        val maxMs: Int,
        val playbackMs: Int,
        val rebufferMs: Int,
        val backBufferMs: Int
    )
}
