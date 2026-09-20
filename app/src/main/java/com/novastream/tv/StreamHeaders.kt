package com.novastream.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@OptIn(UnstableApi::class)
object StreamPlayerFactory {
    data class BuiltPlayer(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
        val networkStats: StreamNetworkStats
    )

    fun buildAdaptive(context: Context, item: PlaylistItem): BuiltPlayer {
        val headers = mutableMapOf<String, String>()
        item.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        val networkStats = StreamNetworkStats()
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(networkStats)
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
        return BuiltPlayer(player, selector, networkStats)
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


class StreamNetworkStats : TransferListener {
    @Volatile var requestStartedMs: Long = 0L
        private set
    @Volatile var firstByteMs: Long = 0L
        private set
    @Volatile var bytesTransferred: Long = 0L
        private set
    @Volatile var transfersStarted: Int = 0
        private set

    override fun onTransferInitializing(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit

    override fun onTransferStart(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) {
        if (!isNetwork) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (requestStartedMs == 0L) requestStartedMs = now
        transfersStarted++
    }

    override fun onBytesTransferred(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        if (!isNetwork) return
        if (firstByteMs == 0L) firstByteMs = android.os.SystemClock.elapsedRealtime()
        this.bytesTransferred += bytesTransferred.toLong()
    }

    override fun onTransferEnd(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit

    fun firstByteDelayMs(): Long =
        if (requestStartedMs > 0L && firstByteMs >= requestStartedMs) firstByteMs - requestStartedMs else -1L
}
