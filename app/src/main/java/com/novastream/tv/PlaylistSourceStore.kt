package com.novastream.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val itemCount: Int,
    val updatedAt: Long,
    val healthy: Boolean,
    val message: String = "",
    val enabled: Boolean = true
)

class PlaylistSourceStore(private val context: Context) {
    companion object {
        // One comprehensive public directory avoids duplicating the same channels
        // across separate category playlists. Users can still add more sources later.
        const val DEFAULT_NAME = "Public channels"
        const val DEFAULT_URL = "https://iptv-org.github.io/iptv/index.m3u"
    }
    private val prefs = context.getSharedPreferences("novastream_sources", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "playlist_sources").apply { mkdirs() }

    fun all(): List<PlaylistSource> {
        val arr = runCatching { JSONArray(prefs.getString("sources", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                PlaylistSource(
                    o.getString("id"), o.getString("name"), o.getString("url"),
                    o.optInt("itemCount"), o.optLong("updatedAt"), o.optBoolean("healthy"),
                    o.optString("message"),
                    if (o.has("enabled")) o.optBoolean("enabled", true) else true
                )
            }.getOrNull()
        }
    }

    fun upsert(source: PlaylistSource, body: String? = null) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == source.id }
        if (index >= 0) list[index] = source else list.add(source)
        saveList(list)
        if (body != null) File(dir, "${source.id}.m3u").writeText(body)
    }

    fun hasSources(): Boolean = all().isNotEmpty()

    fun create(name: String, url: String, body: String, count: Int): PlaylistSource {
        val source = PlaylistSource(UUID.randomUUID().toString(), name, url, count, System.currentTimeMillis(), true, "Ready", true)
        upsert(source, body)
        return source
    }

    fun delete(id: String) {
        saveList(all().filterNot { it.id == id })
        File(dir, "$id.m3u").delete()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(enabled = enabled)
            saveList(list)
        }
    }

    fun body(id: String): String = File(dir, "$id.m3u").takeIf { it.exists() }?.readText().orEmpty()

    fun ensureDefaultSource(repo: LibraryRepository): Boolean {
        if (hasSources()) {
            rebuildLibrary(repo)
            return false
        }
        val result = RemoteSourceLoader.fetch(DEFAULT_URL)
        if (!result.ok) return false
        val count = runCatching { M3uParser.parse(result.body).size }.getOrDefault(0)
        if (count <= 0) return false
        create(DEFAULT_NAME, DEFAULT_URL, result.body, count)
        rebuildLibrary(repo)
        return true
    }

    fun rebuildLibrary(repo: LibraryRepository) {
        val bodies = all().filter { it.enabled }.map { body(it.id).trim() }.filter { it.isNotBlank() }
        if (bodies.isEmpty()) {
            repo.savePlaylist("")
            return
        }

        val unique = LinkedHashMap<String, PlaylistItem>()
        bodies.forEach { raw ->
            M3uParser.parse(raw).forEach { item ->
                val key = item.streamUrl.substringBefore('|').trim().lowercase().trimEnd('/')
                unique.putIfAbsent(key, item)
            }
        }

        val merged = buildString {
            appendLine("#EXTM3U")
            unique.values.forEach { item ->
                val attrs = buildList {
                    item.tvgId?.let { add("tvg-id=" + quoted(it)) }
                    item.tvgName?.let { add("tvg-name=" + quoted(it)) }
                    item.logoUrl?.let { add("tvg-logo=" + quoted(it)) }
                    item.groupTitle?.let { add("group-title=" + quoted(it)) }
                    item.country?.let { add("tvg-country=" + quoted(it)) }
                }.joinToString(" ")
                appendLine("#EXTINF:-1 " + attrs + "," + item.name)
                item.userAgent?.let { appendLine("#EXTVLCOPT:http-user-agent=" + it) }
                item.referer?.let { appendLine("#EXTVLCOPT:http-referrer=" + it) }
                appendLine(item.streamUrl)
            }
        }
        repo.savePlaylist(merged)
    }

    private fun quoted(value: String): String =
        34.toChar().toString() + value.replace(34.toChar(), 39.toChar()) + 34.toChar()

    private fun saveList(list: List<PlaylistSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("itemCount", s.itemCount); put("updatedAt", s.updatedAt)
                put("healthy", s.healthy); put("message", s.message)
                put("enabled", s.enabled)
            })
        }
        prefs.edit().putString("sources", arr.toString()).apply()
    }
}
