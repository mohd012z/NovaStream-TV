package com.novastream.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@OptIn(UnstableApi::class)
object StreamPlayerFactory {
    data class BuiltPlayer(val player: ExoPlayer, val trackSelector: DefaultTrackSelector)

    fun buildAdaptive(context: Context, item: PlaylistItem): BuiltPlayer {
        val headers = mutableMapOf<String, String>()
        item.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)
            .apply { item.userAgent?.takeIf { it.isNotBlank() }?.let { setUserAgent(it) } }

        val sourceFactory: DataSource.Factory = when (item.kind) {
            MediaKind.MOVIE, MediaKind.SERIES -> MediaCache.dataSourceFactory(context, http)
            else -> http
        }

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .build()
        }
        val loadControl = DefaultLoadControl.Builder().apply {
            when (item.kind) {
                MediaKind.LIVE, MediaKind.UNKNOWN -> setBufferDurationsMs(
                    2_000,   // live: avoid building a large delay behind the live edge
                    12_000,
                    250,     // first frame quickly
                    750      // a little more safety after an actual rebuffer
                )
                MediaKind.SERIES -> setBufferDurationsMs(
                    5_000,
                    30_000,
                    500,
                    1_500
                )
                MediaKind.MOVIE -> setBufferDurationsMs(
                    10_000,  // long-form VOD: favor stability once playback has begun
                    60_000,
                    750,
                    2_000
                )
            }
            setPrioritizeTimeOverSizeThresholds(true)
            setBackBuffer(if (item.kind == MediaKind.LIVE || item.kind == MediaKind.UNKNOWN) 3_000 else 15_000, true)
        }.build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(sourceFactory))
            .build()
        return BuiltPlayer(player, selector)
    }

    fun build(context: Context, item: PlaylistItem): ExoPlayer = buildAdaptive(context, item).player

    fun mediaItem(item: PlaylistItem): MediaItem {
        val clean = item.streamUrl.substringBefore('?').lowercase()
        val mime = when {
            clean.endsWith(".m3u8") || clean.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return MediaItem.Builder()
            .setUri(item.streamUrl)
            .apply { if (mime != null) setMimeType(mime) }
            .build()
    }
}
