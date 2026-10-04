package com.novastream.tv

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

enum class StreamHealth { HEALTHY, SLOW, DEAD, UNSAFE, UNKNOWN }

data class StreamProbeResult(
    val url: String,
    val health: StreamHealth,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val reason: String = ""
)

/**
 * Conservative reachability probe. It never deletes a stream: callers should
 * quarantine repeated DEAD results and keep UNKNOWN/SLOW entries available.
 */
object StreamHealthProbe {
    fun probe(rawUrl: String, connectTimeoutMs: Int = 3500, readTimeoutMs: Int = 3500): StreamProbeResult {
        val started = System.currentTimeMillis()
        return runCatching {
            val uri = URI(rawUrl.substringBefore('|').trim())
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                return StreamProbeResult(rawUrl, StreamHealth.UNSAFE, 0, reason = "Unsupported URL")
            }
            val address = InetAddress.getByName(uri.host)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
                return StreamProbeResult(rawUrl, StreamHealth.UNSAFE, 0, reason = "Private/local address")
            }

            val connection = URL(uri.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMs.coerceIn(1000, 8000)
            connection.readTimeout = readTimeoutMs.coerceIn(1000, 8000)
            connection.requestMethod = "GET"
            connection.setRequestProperty("Range", "bytes=0-1023")
            connection.setRequestProperty("User-Agent", "NovaStream-TV/health")
            val code = connection.responseCode
            val latency = System.currentTimeMillis() - started
            connection.disconnect()
            val health = when {
                code in 200..399 && latency <= 2500 -> StreamHealth.HEALTHY
                code in 200..399 -> StreamHealth.SLOW
                code == 401 || code == 403 || code == 451 -> StreamHealth.UNKNOWN
                else -> StreamHealth.DEAD
            }
            StreamProbeResult(rawUrl, health, latency, code, "HTTP $code")
        }.getOrElse { error ->
            StreamProbeResult(rawUrl, StreamHealth.DEAD, System.currentTimeMillis() - started, reason = error.javaClass.simpleName)
        }
    }
}
