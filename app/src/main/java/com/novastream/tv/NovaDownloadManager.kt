package com.novastream.tv

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.concurrent.Executors

object NovaDownloadManager {
    @Volatile private var manager: DownloadManager? = null
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): DownloadManager {
        manager?.let { return it }
        val app = context.applicationContext
        val database = StandaloneDatabaseProvider(app)
        val persistentCache = SimpleCache(
            File(app.filesDir, "offline_media"),
            NoOpCacheEvictor(),
            database
        )
        cache = persistentCache
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        return DownloadManager(
            app,
            database,
            persistentCache,
            http,
            Executors.newFixedThreadPool(3)
        ).apply {
            maxParallelDownloads = 3
            requirements = Requirements(Requirements.NETWORK)
            manager = this
        }
    }
}
