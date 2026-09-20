package com.novastream.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(CompositeTransferListener(networkStats, bandwidthMeter))
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
                    8_000,   // maintain a real live safety reserve after fast startup
                    24_000,
                    500,     // still start quickly
                    2_000    // resume only after rebuilding a useful reserve
                )
                MediaKind.SERIES -> setBufferDurationsMs(
                    15_000,
                    60_000,
                    750,
                    3_000
                )
                MediaKind.MOVIE -> setBufferDurationsMs(
                    20_000,  // long-form VOD: build a deeper reservoir once playback starts
                    90_000,
                    750,
                    4_000
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
            .setBandwidthMeter(bandwidthMeter)
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
    @Volatile var lastByteMs: Long = 0L
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (firstByteMs == 0L) firstByteMs = now
        lastByteMs = now
        this.bytesTransferred += bytesTransferred.toLong()
    }

    override fun onTransferEnd(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit

    fun firstByteDelayMs(): Long =
        if (requestStartedMs > 0L && firstByteMs >= requestStartedMs) firstByteMs - requestStartedMs else -1L

    fun ageSinceLastByteMs(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Long =
        if (lastByteMs > 0L) (nowMs - lastByteMs).coerceAtLeast(0L) else Long.MAX_VALUE

    fun snapshot(): NetworkSnapshot = NetworkSnapshot(
        atMs = android.os.SystemClock.elapsedRealtime(),
        totalBytes = bytesTransferred,
        lastByteMs = lastByteMs
    )

    data class NetworkSnapshot(val atMs: Long, val totalBytes: Long, val lastByteMs: Long) {
        fun mbpsSince(previous: NetworkSnapshot): Double {
            val elapsed = (atMs - previous.atMs).coerceAtLeast(1L)
            val bytes = (totalBytes - previous.totalBytes).coerceAtLeast(0L)
            return (bytes * 8.0) / elapsed / 1000.0
        }
    }
}


private class CompositeTransferListener(
    private vararg val delegates: TransferListener
) : TransferListener {
    override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) =
        delegates.forEach { it.onTransferInitializing(source, dataSpec, isNetwork) }
    override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) =
        delegates.forEach { it.onTransferStart(source, dataSpec, isNetwork) }
    override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) =
        delegates.forEach { it.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred) }
    override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) =
        delegates.forEach { it.onTransferEnd(source, dataSpec, isNetwork) }
}
