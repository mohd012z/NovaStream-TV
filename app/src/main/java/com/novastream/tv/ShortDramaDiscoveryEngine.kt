package com.novastream.tv

/**
 * Product model for mobile-first short drama discovery.
 *
 * This intentionally discovers metadata only. Playback must come from an
 * authorized/public source already verified by NovaStream-TV.
 */
data class ShortDramaFilter(
    val region: String = "Asia",
    val countries: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val maxEpisodeMinutes: Int = 10,
    val onlyFreeOrAds: Boolean = false
)

data class ShortDramaShelf(
    val title: String,
    val originCountries: Set<String> = emptySet(),
    val originalLanguages: Set<String> = emptySet()
)

object ShortDramaDiscoveryEngine {
    val asianShelves = listOf(
        ShortDramaShelf("K-Drama Shorts", setOf("KR"), setOf("ko")),
        ShortDramaShelf("C-Drama Shorts", setOf("CN"), setOf("zh")),
        ShortDramaShelf("J-Drama Shorts", setOf("JP"), setOf("ja")),
        ShortDramaShelf("Thai Drama Shorts", setOf("TH"), setOf("th")),
        ShortDramaShelf("Malaysia Drama Shorts", setOf("MY"), setOf("ms")),
        ShortDramaShelf("Indonesia Drama Shorts", setOf("ID"), setOf("id")),
        ShortDramaShelf("Philippines Drama Shorts", setOf("PH"), setOf("tl")),
        ShortDramaShelf("India Drama Shorts", setOf("IN"), setOf("hi", "ta", "te"))
    )

    /**
     * TMDB discover parameters for metadata discovery.
     * A backend/API-key layer should execute this request; never store secrets here.
     */
    fun tmdbDiscoverParams(filter: ShortDramaFilter, page: Int = 1): Map<String, String> {
        val params = linkedMapOf(
            "page" to page.coerceAtLeast(1).toString(),
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "with_runtime.lte" to filter.maxEpisodeMinutes.coerceIn(1, 30).toString()
        )
        if (filter.countries.isNotEmpty()) params["with_origin_country"] = filter.countries.joinToString("|")
        if (filter.languages.size == 1) params["with_original_language"] = filter.languages.first()
        if (filter.onlyFreeOrAds) params["with_watch_monetization_types"] = "free|ads"
        return params
    }

    fun isShortEpisode(runtimeMinutes: Int?): Boolean =
        runtimeMinutes != null && runtimeMinutes in 1..10
}
