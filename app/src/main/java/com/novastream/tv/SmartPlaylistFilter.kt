package com.novastream.tv

/**
 * Pure playlist sanitation used before a downloaded playlist enters the library.
 * Network reachability is intentionally a separate runtime concern: a temporary
 * outage must not permanently erase a channel from a user's saved source.
 */
object SmartPlaylistFilter {
    fun normalize(raw: String): String {
        val lines = raw.lineSequence().map { it.trim() }.toList()
        val output = mutableListOf("#EXTM3U")
        val seen = linkedSetOf<String>()
        var pendingExtInf: String? = null
        val pendingOptions = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingExtInf = line
                    pendingOptions.clear()
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> pendingOptions += line
                line.startsWith("#") || line.isBlank() -> Unit
                else -> {
                    val url = line.substringBefore('|').trim()
                    val valid = url.startsWith("https://", true) || url.startsWith("http://", true)
                    val key = url.lowercase().trimEnd('/')
                    if (valid && seen.add(key)) {
                        pendingExtInf?.let(output::add)
                        output.addAll(pendingOptions)
                        output += line
                    }
                    pendingExtInf = null
                    pendingOptions.clear()
                }
            }
        }
        return output.joinToString("\n", postfix = "\n")
    }
}
