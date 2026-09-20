package com.novastream.tv

enum class MediaKind { LIVE, MOVIE, SERIES, UNKNOWN }

enum class ContentRating(val label: String) {
    ALL_AGES("All Ages"),
    TEEN("Teen"),
    MATURE("Mature"),
    UNRATED("Unrated")
}

data class PlaylistItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val country: String? = null,
    val contentRating: ContentRating = ContentRating.UNRATED,
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
