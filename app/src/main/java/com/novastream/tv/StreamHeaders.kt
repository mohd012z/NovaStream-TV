package com.novastream.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object StreamPlayerFactory {
    data class BuiltPlayer(val player: ExoPlayer, val trackSelector: DefaultTrackSelector)

    // Reuse one connection pool across every player/channel. This is especially
    // useful for HLS where playlists and media segments are many small requests.
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun upstreamDataSourceFactory(item: PlaylistItem): DataSource.Factory {
        val headers = mutableMapOf<String, String>()
        item.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        val userAgent = item.userAgent?.takeIf { it.isNotBlank() } ?: "NovaStream-TV/2.0"
        val http: DataSource.Factory = if (item.streamUrl.startsWith("http", ignoreCase = true)) {
            OkHttpDataSource.Factory(okHttpClient)
                .setDefaultRequestProperties(headers)
                .setUserAgent(userAgent)
        } else {
            // Keep local/non-HTTP compatibility instead of forcing OkHttp.
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(userAgent)
        }

        return http
    }

    fun buildAdaptive(context: Context, item: PlaylistItem): BuiltPlayer {
        val http = upstreamDataSourceFactory(item)

        // MovieBox-style lesson worth keeping: cache seekable/episodic VOD, but
        // never cache live television. Shorts benefit because replay, back-swipe,
        // and the next episode can reuse already fetched media segments.
        val sourceFactory: DataSource.Factory = when (item.kind) {
            MediaKind.MOVIE, MediaKind.SERIES, MediaKind.SHORT_DRAMA,
            MediaKind.MUSIC, MediaKind.MUSIC_VIDEO -> MediaCache.dataSourceFactory(context, http)
            MediaKind.LIVE, MediaKind.UNKNOWN -> http
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
        val isShort = item.kind == MediaKind.SHORT_DRAMA
        val minBufferMs = when {
            isLive -> 12_000
            isShort -> 5_000
            else -> 10_000
        }
        val maxBufferMs = when {
            isLive -> 45_000
            isShort -> 30_000
            else -> 60_000
        }
        val startBufferMs = when {
            isLive -> 2_000
            isShort -> 600
            else -> 1_000
        }
        val rebufferMs = when {
            isLive -> 4_000
            isShort -> 1_500
            else -> 2_500
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                startBufferMs,
                rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true) // catch up to live edge / start faster instead of waiting on byte thresholds
            .setBackBuffer(when {
                isLive -> 10_000
                isShort -> 8_000
                else -> 30_000
            }, true) // trim old buffered media so seeking/rebuffering stays cheap
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        // A number of IPTV HLS feeds use MPEG-TS segments without AUDs or
        // conventional IDR keyframes. Media3 documents these as a cause of
        // apparently permanent buffering. Use the compatibility extractor only
        // for HLS/live-style sources; ordinary VOD keeps the default fast path.
        val tsCompatibilityFlags =
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        val hlsFactory = HlsMediaSource.Factory(sourceFactory)
            .setExtractorFactory(DefaultHlsExtractorFactory(tsCompatibilityFlags, true))
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(sourceFactory)
            .setServerSideAdInsertionMediaSourceFactory(hlsFactory)

        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        return BuiltPlayer(player, selector)
    }

    fun build(context: Context, item: PlaylistItem): ExoPlayer = buildAdaptive(context, item).player

    fun mediaItem(item: PlaylistItem): MediaItem {
        val url = item.streamUrl.trim()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mimeType = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }

        val builder = MediaItem.Builder().setUri(url)
        if (mimeType != null) builder.setMimeType(mimeType)
        return builder.build()
    }
}
