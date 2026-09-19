package com.novastream.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RemoteSourceResult(val ok: Boolean, val code: Int, val body: String, val message: String)

object RemoteSourceLoader {
    const val PERFECTTV_FREE_M3U = "https://ptv2026.com/PerfecttvFree3.m3u"
    const val PERFECTTV_FREE_EPG = "https://ptv2026.com/EPGPerfecttv/epgtvku.xml"

    // Guards against a malicious/misbehaving source sending an unbounded or
    // slow-drip response that would otherwise be buffered fully into memory.
    private const val MAX_RESPONSE_BYTES = 20L * 1024 * 1024 // 20 MB

    fun fetch(url: String): RemoteSourceResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PerfectTV-Enhanced/1.0")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) }.orEmpty()
            RemoteSourceResult(code in 200..299, code, body, if (code in 200..299) "OK" else "HTTP $code")
        } catch (e: Exception) {
            RemoteSourceResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream, maxBytes: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IOException("Response exceeded max allowed size of $maxBytes bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
