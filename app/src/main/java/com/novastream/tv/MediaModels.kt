package com.novastream.tv

enum class MediaKind { LIVE, MOVIE, SERIES, UNKNOWN }

data class PlaylistItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val kind: MediaKind = MediaKind.UNKNOWN
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val stopMs: Long
)
