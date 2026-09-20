package com.novastream.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Lightweight persistent knowledge about media endpoints.
 * Stores classification/performance observations only; never stores credentials,
 * cookies, authorization headers or DRM material.
 */
class NovaProbeCache(context: Context) {
    data class ProbeRecord(
        val key: String,
        val container: NovaStreamLibrary.Container,
        val profile: NovaStreamLibrary.PlaybackProfile,
        val mimeType: String?,
        val isLive: Boolean?,
        val isSeekable: Boolean?,
        val startupMs: Long,
        val firstByteMs: Long,
        val lastHealthyAtMs: Long,
        val failures: Int
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("nova_probe_cache_v1", Context.MODE_PRIVATE)

    fun keyFor(url: String): String {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        val stable = buildString {
            append(uri?.scheme?.lowercase().orEmpty())
            append("://")
            append(uri?.host?.lowercase().orEmpty())
            append(uri?.path.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(url: String): ProbeRecord? {
        val key = keyFor(url)
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            ProbeRecord(
                key = key,
                container = NovaStreamLibrary.Container.valueOf(j.getString("container")),
                profile = NovaStreamLibrary.PlaybackProfile.valueOf(j.getString("profile")),
                mimeType = j.optString("mimeType").takeIf { it.isNotBlank() },
                isLive = if (j.has("isLive")) j.getBoolean("isLive") else null,
                isSeekable = if (j.has("isSeekable")) j.getBoolean("isSeekable") else null,
                startupMs = j.optLong("startupMs", -1L),
                firstByteMs = j.optLong("firstByteMs", -1L),
                lastHealthyAtMs = j.optLong("lastHealthyAtMs", 0L),
                failures = j.optInt("failures", 0)
            )
        }.getOrNull()
    }

    fun observe(
        item: PlaylistItem,
        isLive: Boolean?,
        isSeekable: Boolean?,
        startupMs: Long = -1L,
        firstByteMs: Long = -1L,
        healthy: Boolean = false
    ) {
        val descriptor = NovaStreamLibrary.describe(item.streamUrl, item.kind, item.groupTitle)
        val old = get(item.streamUrl)
        val effectiveProfile = when {
            isLive == true -> NovaStreamLibrary.PlaybackProfile.LIVE
            isLive == false && descriptor.profile == NovaStreamLibrary.PlaybackProfile.AUTO ->
                if (descriptor.container == NovaStreamLibrary.Container.AUDIO)
                    NovaStreamLibrary.PlaybackProfile.MUSIC
                else NovaStreamLibrary.PlaybackProfile.CINEMA
            else -> descriptor.profile
        }
        val key = keyFor(item.streamUrl)
        val j = JSONObject()
            .put("container", descriptor.container.name)
            .put("profile", effectiveProfile.name)
            .put("mimeType", descriptor.mimeType.orEmpty())
            .put("startupMs", if (startupMs >= 0) startupMs else old?.startupMs ?: -1L)
            .put("firstByteMs", if (firstByteMs >= 0) firstByteMs else old?.firstByteMs ?: -1L)
            .put("lastHealthyAtMs", if (healthy) System.currentTimeMillis() else old?.lastHealthyAtMs ?: 0L)
            .put("failures", old?.failures ?: 0)
        isLive?.let { j.put("isLive", it) }
        isSeekable?.let { j.put("isSeekable", it) }
        prefs.edit().putString(key, j.toString()).apply()
    }

    fun markFailure(url: String) {
        val old = get(url) ?: return
        val raw = prefs.getString(old.key, null) ?: return
        runCatching {
            val j = JSONObject(raw)
            j.put("failures", old.failures + 1)
            prefs.edit().putString(old.key, j.toString()).apply()
        }
    }

    fun clear() = prefs.edit().clear().apply()
}
