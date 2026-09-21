package com.novastream.tv

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class RemoteSourceResult(val ok: Boolean, val code: Int, val body: String, val message: String)

object RemoteSourceLoader {
    const val PERFECTTV_FREE_M3U = "https://ptv2026.com/PerfecttvFree3.m3u"
    const val PERFECTTV_FREE_EPG = "https://ptv2026.com/EPGPerfecttv/epgtvku.xml"

    fun fetch(url: String): RemoteSourceResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NovaStreamerTV/2.0")
            setRequestProperty("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, application/xml, text/xml, text/plain, */*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        return try {
            val code = conn.responseCode
            val raw = if (code in 200..299) conn.inputStream else conn.errorStream
            val stream: InputStream? = if (raw != null && conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            val charset = conn.contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { charset(it) }.getOrNull() }
                ?: Charsets.UTF_8
            val body = stream?.bufferedReader(charset)?.use { it.readText() }.orEmpty()
            RemoteSourceResult(code in 200..299, code, body, if (code in 200..299) "OK" else "HTTP $code")
        } catch (e: Exception) {
            RemoteSourceResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
