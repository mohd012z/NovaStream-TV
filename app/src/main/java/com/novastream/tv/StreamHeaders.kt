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
            // 8s connect balances fast failover against slow/overloaded IPTV servers;
            // going much lower (e.g. 5s) caused spurious connect failures on some sources.
            .setConnectTimeoutMs(8_000)
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

        // Live and VOD want different tradeoffs: live benefits from a smaller min/max buffer
        // window (less to fill before the stream is considered "live enough" to start, and
        // faster catch-up to the live edge after a stall), while VOD keeps a larger prebuffer
        // so adaptive-bitrate seeking stays smooth. bufferForPlayback(AfterRebuffer)Ms is kept
        // in the 1000-1500ms range for live (up from the previous 350/750ms shared value) since
        // an extremely small buffer here caused stutter right after the first frame on slower
        // IPTV origins; VOD keeps a smaller buffer requirement since its source is cached/seekable.
        val isLive = item.kind == MediaKind.LIVE || item.kind == MediaKind.UNKNOWN
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isLive) 5_000 else 6_000,
                if (isLive) 20_000 else 40_000,
                if (isLive) 1_200 else 500,
                if (isLive) 1_500 else 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true) // catch up to live edge / start faster instead of waiting on byte thresholds
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
