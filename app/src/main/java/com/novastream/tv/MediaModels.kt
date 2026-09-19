package com.novastream.tv

enum class MediaKind { LIVE, MOVIE, SERIES, UNKNOWN }\n\nenum class ContentRating(val label: String) {\n    ALL_AGES("All Ages"),\n    TEEN("Teen"),\n    MATURE("Mature"),\n    UNRATED("Unrated")\n}

data class PlaylistItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,\n    val year: Int? = null,\n    val genre: String? = null,\n    val country: String? = null,\n    val contentRating: ContentRating = ContentRating.UNRATED,
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
