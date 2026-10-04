package com.novastream.tv

/** Public, user-selectable sources. Nothing here is auto-activated. */
data class PublicPlaylistPreset(val name: String, val url: String, val section: String)

object PublicPlaylistCatalog {
    val presets = listOf(
        PublicPlaylistPreset("Malaysia", "https://iptv-org.github.io/iptv/countries/my.m3u", "Local"),
        PublicPlaylistPreset("ASEAN", "https://iptv-org.github.io/iptv/regions/asean.m3u", "Regional"),
        PublicPlaylistPreset("Southeast Asia", "https://iptv-org.github.io/iptv/regions/sea.m3u", "Regional"),
        PublicPlaylistPreset("Movies", "https://iptv-org.github.io/iptv/categories/movies.m3u", "Entertainment"),
        PublicPlaylistPreset("Series", "https://iptv-org.github.io/iptv/categories/series.m3u", "Entertainment"),
        PublicPlaylistPreset("Entertainment", "https://iptv-org.github.io/iptv/categories/entertainment.m3u", "Entertainment"),
        PublicPlaylistPreset("Comedy", "https://iptv-org.github.io/iptv/categories/comedy.m3u", "Entertainment"),
        PublicPlaylistPreset("Animation", "https://iptv-org.github.io/iptv/categories/animation.m3u", "Family"),
        PublicPlaylistPreset("Family", "https://iptv-org.github.io/iptv/categories/family.m3u", "Family"),
        PublicPlaylistPreset("Kids", "https://iptv-org.github.io/iptv/categories/kids.m3u", "Family"),
        PublicPlaylistPreset("News", "https://iptv-org.github.io/iptv/categories/news.m3u", "Information"),
        PublicPlaylistPreset("Documentary", "https://iptv-org.github.io/iptv/categories/documentary.m3u", "Information"),
        PublicPlaylistPreset("Education", "https://iptv-org.github.io/iptv/categories/education.m3u", "Information"),
        PublicPlaylistPreset("Science", "https://iptv-org.github.io/iptv/categories/science.m3u", "Information"),
        PublicPlaylistPreset("Sports", "https://iptv-org.github.io/iptv/categories/sports.m3u", "Sports"),
        PublicPlaylistPreset("Music", "https://iptv-org.github.io/iptv/categories/music.m3u", "Lifestyle"),
        PublicPlaylistPreset("Cooking", "https://iptv-org.github.io/iptv/categories/cooking.m3u", "Lifestyle"),
        PublicPlaylistPreset("Travel", "https://iptv-org.github.io/iptv/categories/travel.m3u", "Lifestyle"),
        PublicPlaylistPreset("Outdoor", "https://iptv-org.github.io/iptv/categories/outdoor.m3u", "Lifestyle")
    )
}
