package com.novastream.tv

import android.content.Context
import java.io.File

class LibraryRepository(private val context: Context) {
    private val playlistFile = File(context.filesDir, "novastream_playlist.m3u")
    private val epgFile = File(context.filesDir, "novastream_epg.xml")

    fun savePlaylist(raw: String) = playlistFile.writeText(raw)
    fun saveEpg(raw: String) = epgFile.writeText(raw)
    fun playlistRaw(): String = if (playlistFile.exists()) playlistFile.readText() else ""
    fun epgRaw(): String = if (epgFile.exists()) epgFile.readText() else ""
    fun playlist(): List<PlaylistItem> = runCatching { M3uParser.parse(playlistRaw()) }.getOrDefault(emptyList())
    fun epg(): List<EpgProgramme> = runCatching { EpgParser.parse(epgRaw()) }.getOrDefault(emptyList())
    fun clear() { playlistFile.delete(); epgFile.delete() }
}
