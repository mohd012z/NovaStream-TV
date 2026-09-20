package com.novastream.tv

import java.net.URI

data class PublicM3uCandidate(val name: String, val url: String, val region: String = "World", val provider: String = "Public directory")
data class PublicM3uValidation(val candidate: PublicM3uCandidate, val valid: Boolean, val itemCount: Int = 0, val message: String)

object PublicM3uDiscoveryEngine {
    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"

    private val regions = linkedMapOf(
        "Asia" to "asia", "Asia-Pacific" to "apac", "ASEAN" to "asean",
        "Southeast Asia" to "sea", "East Asia" to "eas", "South Asia" to "sas",
        "Central Asia" to "cas", "West Asia" to "was", "Worldwide" to "ww"
    )
    private val asianCountries = linkedMapOf(
        "Malaysia" to "my", "Singapore" to "sg", "Indonesia" to "id", "Thailand" to "th",
        "Philippines" to "ph", "Vietnam" to "vn", "Brunei" to "bn", "Cambodia" to "kh",
        "Laos" to "la", "Myanmar" to "mm", "Japan" to "jp", "South Korea" to "kr",
        "India" to "in", "Pakistan" to "pk", "Bangladesh" to "bd", "Sri Lanka" to "lk",
        "Nepal" to "np", "Mongolia" to "mn"
    )
    private val categories = listOf("news", "sports", "family", "movies", "music", "documentary")

    fun discover(query: String): List<PublicM3uCandidate> {
        val q = query.trim().lowercase()
        val regional = regions.map { (name, code) ->
            PublicM3uCandidate(name + " public channels", IPTV_ORG + "/regions/" + code + ".m3u", name, "iptv-org")
        }
        val countries = asianCountries.map { (name, code) ->
            PublicM3uCandidate(name + " public channels", IPTV_ORG + "/countries/" + code + ".m3u", "Asia", "iptv-org")
        }
        val grouped = categories.map {
            PublicM3uCandidate(it.replaceFirstChar(Char::uppercase) + " public channels", IPTV_ORG + "/categories/" + it + ".m3u", "World", "iptv-org")
        }
        val all = regional + countries + grouped
        if (q.isBlank() || q == "all") return all
        return all.filter {
            it.name.lowercase().contains(q) || it.region.lowercase().contains(q) ||
                it.provider.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    fun validate(candidate: PublicM3uCandidate): PublicM3uValidation {
        if (!isSafeHttpUrl(candidate.url)) return PublicM3uValidation(candidate, false, message = "Unsupported URL")
        val result = RemoteSourceLoader.fetch(candidate.url)
        if (!result.ok) return PublicM3uValidation(candidate, false, message = result.message)
        val body = result.body.removePrefix("\uFEFF").trimStart()
        if (!body.startsWith("#EXTM3U", ignoreCase = true)) return PublicM3uValidation(candidate, false, message = "Missing #EXTM3U header")
        val count = runCatching { M3uParser.parse(body).size }.getOrDefault(0)
        return if (count > 0) PublicM3uValidation(candidate, true, count, "Verified #EXTM3U - " + count + " items")
        else PublicM3uValidation(candidate, false, message = "#EXTM3U found but no playable entries")
    }

    fun normalizedKey(url: String): String = runCatching {
        val u = URI(url.trim())
        val port = if (u.port == -1) "" else ":" + u.port
        (u.scheme.lowercase() + "://" + u.host.lowercase() + port + u.path).trimEnd('/')
    }.getOrElse { url.trim().lowercase().trimEnd('/') }

    private fun isSafeHttpUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        (u.scheme.equals("https", true) || u.scheme.equals("http", true)) && !u.host.isNullOrBlank()
    }.getOrDefault(false)
}
