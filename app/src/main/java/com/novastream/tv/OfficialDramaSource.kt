package com.novastream.tv

import java.net.URI

enum class DramaAccess { PUBLIC_STREAM, OFFICIAL_PAGE, METADATA_ONLY }
enum class DramaForm { FULL, EPISODES, SHORT, CLIP, UNKNOWN }

data class OfficialDramaSource(
    val provider: String,
    val title: String,
    val url: String,
    val country: String? = null,
    val language: String? = null,
    val runtimeMinutes: Int? = null,
    val access: DramaAccess = DramaAccess.OFFICIAL_PAGE,
    val form: DramaForm = DramaForm.UNKNOWN,
    val verifiedAt: Long = System.currentTimeMillis()
)

data class DramaSourceValidation(
    val valid: Boolean,
    val playableInApp: Boolean,
    val message: String
)

/**
 * Safety boundary between discovery and playback.
 *
 * Metadata/provider pages are useful discovery results but are never treated as
 * playable streams. Only a direct public HTTP(S) media URL that passes normal
 * NovaStream validation may become PUBLIC_STREAM.
 */
object OfficialDramaSourceValidator {
    fun validate(source: OfficialDramaSource): DramaSourceValidation {
        if (!isHttpUrl(source.url)) return DramaSourceValidation(false, false, "Unsupported URL")

        return when (source.access) {
            DramaAccess.METADATA_ONLY ->
                DramaSourceValidation(true, false, "Metadata only")
            DramaAccess.OFFICIAL_PAGE ->
                DramaSourceValidation(true, false, "Open official provider")
            DramaAccess.PUBLIC_STREAM -> {
                val looksMedia = source.url.substringBefore('?').lowercase().let {
                    it.endsWith(".m3u8") || it.endsWith(".m3u")
                }
                if (!looksMedia) DramaSourceValidation(false, false, "Direct public media URL required")
                else DramaSourceValidation(true, true, "Public stream candidate")
            }
        }
    }

    fun classify(title: String, runtimeMinutes: Int?): DramaForm {
        val t = title.lowercase()
        return when {
            "trailer" in t || "teaser" in t || "clip" in t -> DramaForm.CLIP
            "full" in t || "complete" in t -> DramaForm.FULL
            Regex("""\b(ep|episode)\s*\d+""").containsMatchIn(t) -> DramaForm.EPISODES
            runtimeMinutes != null && runtimeMinutes in 1..10 -> DramaForm.SHORT
            else -> DramaForm.UNKNOWN
        }
    }

    fun deduplicate(items: List<OfficialDramaSource>): List<OfficialDramaSource> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val key = normalized(item.url) + "|" + item.title.trim().lowercase()
            seen.add(key)
        }
    }

    private fun normalized(url: String): String = runCatching {
        val u = URI(url.trim())
        val port = if (u.port == -1) "" else ":" + u.port
        u.scheme.lowercase() + "://" + u.host.lowercase() + port + u.path.trimEnd('/')
    }.getOrElse { url.trim().lowercase().trimEnd('/') }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        (u.scheme.equals("https", true) || u.scheme.equals("http", true)) && !u.host.isNullOrBlank()
    }.getOrDefault(false)
}
