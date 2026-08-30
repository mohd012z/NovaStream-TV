package com.novastream.tv

import java.net.HttpURLConnection
import java.net.URL

data class RemoteSourceResult(val ok: Boolean, val code: Int, val body: String, val message: String)

object RemoteSourceLoader {
    const val PERFECTTV_FREE_M3U = "https://ptv2026.com/PerfecttvFree3.m3u"
    const val PERFECTTV_FREE_EPG = "https://ptv2026.com/EPGPerfecttv/epgtvku.xml"

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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            RemoteSourceResult(code in 200..299, code, body, if (code in 200..299) "OK" else "HTTP $code")
        } catch (e: Exception) {
            RemoteSourceResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
