package com.novastream.tv

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Lightweight AVMDL-style preload using NovaStream's shared Media3 cache. */
object MediaPreloadManager {
    private const val SHORT_PRELOAD_BYTES = 4L * 1024L * 1024L
    private const val VOD_PRELOAD_BYTES = 8L * 1024L * 1024L
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var task: Future<*>? = null
    @Volatile var state: String = "IDLE"
        private set

    @Synchronized
    fun preload(context: Context, item: PlaylistItem?) {
        task?.cancel(true)
        task = null
        if (item == null || item.kind == MediaKind.LIVE) {
            state = "IDLE"
            return
        }
        val uri = runCatching { android.net.Uri.parse(item.streamUrl) }.getOrNull() ?: run {
            state = "SKIPPED"
            return
        }
        val length = if (item.kind == MediaKind.SHORT_DRAMA) SHORT_PRELOAD_BYTES else VOD_PRELOAD_BYTES
        val upstream = StreamPlayerFactory.upstreamDataSourceFactory(item)
        val factory = MediaCache.dataSourceFactory(context, upstream)
        state = "QUEUED"
        task = executor.submit {
            state = "PRELOADING"
            try {
                val spec = DataSpec.Builder().setUri(uri).setPosition(0).setLength(length).setKey(item.id).build()
                val cacheDataSource = factory.createDataSource() as androidx.media3.datasource.cache.CacheDataSource
                CacheWriter(cacheDataSource, spec, null, null).cache()
                state = "READY"
            } catch (_: InterruptedException) {
                state = "CANCELLED"
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                state = "ERROR"
            }
        }
    }

    @Synchronized
    fun cancel() {
        task?.cancel(true)
        task = null
        state = "IDLE"
    }
}
