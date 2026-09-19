package com.novastream.tv

import android.content.Context
import androidx.media3.common.MediaItem
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                6_000,  // keep enough media for temporary signal drops, without over-buffering before start
                40_000,
                350,    // start playback as soon as a small amount of media is ready
                750     // resume quickly after a rebuffer instead of refilling a large buffer first
            ).setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true) // trim old buffered media so seeking/rebuffering stays cheap
            .build()
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

    fun mediaItem(item: PlaylistItem): MediaItem = MediaItem.fromUri(item.streamUrl)
}
