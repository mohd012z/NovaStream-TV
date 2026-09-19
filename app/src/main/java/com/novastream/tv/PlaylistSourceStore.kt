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
    val message: String = ""
)

class PlaylistSourceStore(private val context: Context) {
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
                    o.optString("message")
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

    fun create(name: String, url: String, body: String, count: Int): PlaylistSource {
        val source = PlaylistSource(UUID.randomUUID().toString(), name, url, count, System.currentTimeMillis(), true, "Ready")
        upsert(source, body)
        return source
    }

    fun delete(id: String) {
        saveList(all().filterNot { it.id == id })
        File(dir, "$id.m3u").delete()
    }

    fun body(id: String): String = File(dir, "$id.m3u").takeIf { it.exists() }?.readText().orEmpty()

    fun rebuildLibrary(repo: LibraryRepository) {
        val bodies = all().map { body(it.id).trim() }.filter { it.isNotBlank() }
        if (bodies.isEmpty()) {
            repo.savePlaylist("")
            return
        }
        val merged = buildString {
            appendLine("#EXTM3U")
            bodies.forEach { raw ->
                raw.lineSequence().filterNot { it.trim().equals("#EXTM3U", true) }.forEach { appendLine(it) }
            }
        }
        repo.savePlaylist(merged)
    }

    private fun saveList(list: List<PlaylistSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("itemCount", s.itemCount); put("updatedAt", s.updatedAt)
                put("healthy", s.healthy); put("message", s.message)
            })
        }
        prefs.edit().putString("sources", arr.toString()).apply()
    }
}
