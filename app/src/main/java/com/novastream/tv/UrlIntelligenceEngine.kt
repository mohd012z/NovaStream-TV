package com.novastream.tv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NovaStreamer URL Intelligence Engine (Anam spec, 2026-10-01).
 *
 * "Not just collecting addresses, but understanding which subsystem owns each
 * endpoint, what triggers it, what it returns, what depends on it, whether it
 * was actually observed, how its behavior changes over time, and what evidence
 * supports every conclusion."
 *
 * Pipeline: DISCOVERY -> NORMALIZATION -> CLASSIFICATION -> PROVENANCE ->
 * CONSTRUCTION -> REDIRECT CHAIN -> RESPONSE SCHEMA -> CHANGE DETECTION ->
 * BEHAVIOR STATS -> DEPENDENCY GRAPH.
 *
 * Pure-Kotlin, zero Android/JSON deps => JVM-testable (tdd/UrlEngineTest.kt).
 *
 * Trust rules honoured here:
 *  - String presence is a FACT; what the program does with it is an INFERENCE
 *    (needs xref / call-site resolution) -> Evidence.staticFound vs xrefResolved.
 *  - A URL embedded in a library does not prove the app contacts it ->
 *    Evidence.runtimeObserved is an independent field.
 *  - Copy / export must never become a credential collector -> [sanitizeUrl].
 *  - One unusual response does not become knowledge -> [EndpointBehavior] only
 *    accumulates statistics; promotion to Memory is the Lola/Kernel gate.
 */
enum class DiscoverySurface { DEX, DEX_DECOMPILED, XML_MANIFEST, XML_RESOURCES, ASSETS, NATIVE_SO, WEBSOCKET, PLAYLIST, M3U_ATTR, APP_CODE, RUNTIME_LOG }

enum class UrlRole { STREAM, PLAYLIST, EPG, REMOTE_CONFIG, NOTIFICATION, DEVICE_HEALTH, API, AUTH, IMAGE, SUBTITLE, WEB_PAGE, WEBSOCKET, ANALYTICS, CDN, UNKNOWN }

/** Independent evidence flags — presence ≠ use. */
enum class EvidenceKind { STATIC_ONLY, RUNTIME_ONLY, STATIC_AND_RUNTIME, INFERRED, STALE }

data class Evidence(
    val staticFound: Boolean = false,
    val xrefResolved: Boolean = false,
    val runtimeObserved: Boolean = false
) {
    val kind: EvidenceKind
        get() = when {
            runtimeObserved && staticFound -> EvidenceKind.STATIC_AND_RUNTIME
            runtimeObserved -> EvidenceKind.RUNTIME_ONLY
            staticFound -> EvidenceKind.STATIC_ONLY
            xrefResolved -> EvidenceKind.INFERRED
            else -> EvidenceKind.STALE
        }

    /** Confidence from evidence + classification strength + resolved provenance. */
    fun confidence(role: UrlRole, construction: Boolean): Double {
        var c = 0.5
        if (runtimeObserved) c += 0.3
        if (xrefResolved) c += 0.15
        if (construction) c += 0.05
        if (role != UrlRole.UNKNOWN) c += 0.05
        return (c * 100.0).toLong() / 100.0 // 2 dp
    }
}

/** Forensic location hierarchy (APK: container/offset/xref; source: file/line). */
data class Location(
    val artifact: String,
    val container: String,            // classes.dex / res/ / assets/ / lib/armeabi-v7a/*.so / repo path
    val byteOffsetHex: String? = null,
    val dexStringIndex: Int? = null,
    val cls: String? = null,
    val method: String? = null,
    val instructionOffsetHex: String? = null,
    val line: Int? = null
) {
    /** `APK └─ classes.dex └─ string @ 0x243970 └─ xref pending` */
    fun hierarchy(): List<String> {
        val out = mutableListOf(artifact, container)
        if (byteOffsetHex != null) out += "string @ $byteOffsetHex"
        if (cls != null) out += cls
        if (method != null) out += method
        if (line != null) out += "line $line"
        out += if (cls != null && method != null) "xref resolved" else "xref pending"
        return out
    }
}

/** BASE + PATH + QUERY (+TOKEN) — recognize URL construction, not only literals. */
data class UrlConstruction(
    val base: String,
    val path: String? = null,
    val query: Map<String, String>? = null,
    val hasToken: Boolean = false
) {
    fun build(): String {
        var b = base.trimEnd('/')
        if (!path.isNullOrEmpty()) {
            val p = if (path.startsWith("/")) path else "/$path"
            b += p
        }
        val q = query?.filterValues { it.isNotEmpty() }
        if (!q.isNullOrEmpty()) {
            b += "?" + q.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        }
        return b
    }

    private fun enc(s: String): String =
        s.map { c ->
            when {
                c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '-' || c == '_' || c == '.' -> c.toString()
                else -> "%%%02X".format(c.code)
            }
        }.joinToString("")

    companion object {
        /** Detect `base+path(+query)` even when the full URL never appears literally. */
        fun construct(base: String, path: String?, query: Map<String, String>?, token: Boolean = false) =
            UrlConstruction(base, path, query, token)
    }
}

data class ResponseSchema(
    val endpointType: UrlRole,
    /** key -> primitive type, e.g. "allowedDomains" -> "array<string>". NO values stored. */
    val shape: Map<String, String>
) {
    fun describe(): String = shape.entries.joinToString(", ") { "${it.key}:${it.value}" }
}

data class UrlRecord(
    val id: String,
    var url: String,
    val role: UrlRole,
    val protocol: String,             // HTTPS / HTTP / WSS / RTSP / ...
    val source: DiscoverySurface,
    val evidence: Evidence,
    val locations: List<Location> = emptyList(),
    val construction: UrlConstruction? = null,
    val method: String? = null,       // GET / POST / ...
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val caller: String? = null,       // class.method
    val trigger: String? = null,      // STARTUP / PLAYLIST_REFRESH / CHANNEL_SELECT / BACKGROUND / MANUAL
    val outputConsumer: String? = null,
    val redirectChain: List<String> = emptyList(),  // original -> hop1 -> ... -> final
    val lastRedirectChain: List<String> = emptyList(),
    val responseSchema: ResponseSchema? = null,
    val behavior: EndpointBehavior? = null,
    val firstSeenMs: Long,
    var lastSeenMs: Long = 0L,
    var trust: String = "UNTRUSTED",   // CONFIGURED / TRUSTED / UNTRUSTED / BLOCKED
    var lastDiff: UrlDiff? = null
) {
    val host: String get() = UrlIntelligence.hostOf(url)

    fun sanitizedCopy(): String = UrlIntelligence.sanitizeUrl(url)

    /** GUI copy payload: host / url / method / call site / dep map / sanitized / json — never raw credentials. */
    fun copy(kind: String): String = when (kind) {
        "HOST" -> host
        "URL" -> sanitizedCopy()
        "METHOD" -> method ?: "GET"
        "CALL_SITE" -> (caller ?: "unresolved") + " @ " + trigger.orEmpty()
        "SANITIZED" -> sanitizedCopy()
        "JSON" -> toSanitizedJson()
        else -> sanitizedCopy()
    }

    fun toSanitizedJson(): String =
        "{ \"id\":\"$id\", \"url\":\"${sanitizedCopy()}\", \"role\":\"${role.name}\", \"protocol\":\"$protocol\"," +
        " \"source\":\"${source.name}\", \"evidence\":\"${evidence.kind.name}\"," +
        " \"caller\":\"${caller ?: ""}\", \"trigger\":\"${trigger ?: ""}\"," +
        " \"redirectChain\":[${redirectChain.joinToString(",") { "\"${it}\"" }}] }"
}

/** Aggregate behavior — one unusual response does not become knowledge. */
data class EndpointBehavior(
    val endpointId: String,
    var successfulChecks: Int = 0,
    var timeouts: Int = 0,
    var schemaChanges: Int = 0,
    var redirectChanges: Int = 0,
    var minLatencyMs: Int? = null,
    var maxLatencyMs: Int? = null,
    var totalLatencyMs: Int = 0,
    var observations: Int = 0,
    var lastVerifiedMs: Long = 0L,
    var typicalLatencyRange: String = "n/a"
) {
    val avgLatencyMs: Int? get() = if (observations > 0) totalLatencyMs / observations else null
}

enum class RelationType {
    CONFIG_PROVIDES_PLAYLIST, CONFIG_PROVIDES_EPG, PLAYLIST_CONTAINS_CHANNEL,
    CHANNEL_HAS_STREAM, CHANNEL_USES_EPG, STREAM_REDIRECTS_TO_HOST, STREAM_USES_CDN
}

data class DependencyEdge(val fromId: String, val toId: String, val type: RelationType)

data class Snapshot(
    val id: String,
    val url: String,
    val host: String,
    val scheme: String,
    val path: String,
    val query: String
)

data class UrlDiff(
    val id: String,
    val hostChanged: Boolean,
    val schemeChanged: Boolean,
    val pathChanged: Boolean,
    val queryChanged: Boolean,
    val previous: Snapshot?,
    val current: Snapshot?
)

data class ChangeHistoryEntry(
    val id: String,
    val tsMs: Long,
    val change: String   // ADDED / REMOVED / HOST_CHANGED / SCHEME_CHANGED / PATH_CHANGED / QUERY_CHANGED / ...
)

// ---------------------------------------------------------------------------
//  Engine
// ---------------------------------------------------------------------------

class UrlIntelligence {

    val records = LinkedHashMap<String, UrlRecord>()   // keyed by endpoint id
    private val urlToId = HashMap<String, String>()
    private val snapshots = LinkedHashMap<String, Snapshot>()   // keyed by endpoint id
    private val history = ArrayList<ChangeHistoryEntry>()
    private var nextId = 0
    var nowMs: Long = 0L

    private fun indexUrl(record: UrlRecord) {
        urlToId[record.url] = record.id
    }

    private fun byId(id: String): UrlRecord? = records[id]
    private fun byUrl(url: String): UrlRecord? = urlToId[UrlIntelligence.normalize(url)]?.let { records[it] }

    /** Stage 1: discover URL literals across the six static surfaces + runtime. */
    fun discover(surface: DiscoverySurface, raw: String): List<String> {
        val out = LinkedHashMap<String, String>()
        val pat = Regex("(?i)\\b(wss?|https?|rtsp?|rtmp)://[^\\s\"'<>`\\])}]+")
        for (m in pat.findAll(raw)) {
            val u = m.value.trimEnd('.', ',', ';', ')', ']')
            out[UrlIntelligence.normalize(u)] = u
        }
        return out.values.toList()
    }

    /** Register / upsert a discovered or constructed URL. One record per endpoint. */
    fun register(
        url: String,
        role: UrlRole = UrlIntelligence.classify(url),
        source: DiscoverySurface,
        location: Location? = null,
        construction: UrlConstruction? = null,
        method: String? = null,
        caller: String? = null,
        trigger: String? = null,
        outputConsumer: String? = null,
        staticFound: Boolean = true,
        runtimeObserved: Boolean = false,
        xrefResolved: Boolean = false,
        endpointId: String? = null
    ): UrlRecord {
        val normalized = UrlIntelligence.normalize(url)
        val existing = endpointId?.let { byId(it) } ?: byUrl(normalized)
        val record = if (existing != null) {
            existing.copy(
                url = normalized,
                role = if (role != UrlRole.UNKNOWN) role else existing.role,
                protocol = UrlIntelligence.protocolOf(normalized),
                evidence = existing.evidence.copy(
                    staticFound = existing.evidence.staticFound || staticFound,
                    runtimeObserved = existing.evidence.runtimeObserved || runtimeObserved,
                    xrefResolved = existing.evidence.xrefResolved || xrefResolved
                ),
                locations = if (location != null && existing.locations.none { it == location }) existing.locations + location else existing.locations,
                construction = construction ?: existing.construction,
                method = method ?: existing.method,
                caller = caller ?: existing.caller,
                trigger = trigger ?: existing.trigger,
                outputConsumer = outputConsumer ?: existing.outputConsumer
            )
        } else {
            val id = endpointId ?: "url_" + nextId.inc().toString().padStart(3, '0')
            UrlRecord(
                id = id,
                url = normalized,
                role = role,
                protocol = UrlIntelligence.protocolOf(normalized),
                source = source,
                evidence = Evidence(staticFound = staticFound, xrefResolved = xrefResolved, runtimeObserved = runtimeObserved),
                locations = if (location != null) listOf(location) else emptyList(),
                construction = construction,
                method = method,
                caller = caller,
                trigger = trigger,
                outputConsumer = outputConsumer,
                firstSeenMs = nowMs
            )
        }
        record.lastSeenMs = nowMs
        records[record.id] = record
        indexUrl(record)
        return record
    }

    /** Redirect chain: original -> hop1 -> ... -> final. Endpoint-stable via endpointId. */
    fun observe(url: String, redirectChain: List<String>, latencyMs: Int, success: Boolean, schema: ResponseSchema? = null, endpointId: String? = null): UrlRecord {
        val normalized = UrlIntelligence.normalize(url)
        val record = (endpointId?.let { byId(it) }) ?: byUrl(normalized)
            ?: register(normalized, source = DiscoverySurface.RUNTIME_LOG, staticFound = false, runtimeObserved = true, endpointId = endpointId)
        val changedChain = record.redirectChain.isNotEmpty() && record.redirectChain != redirectChain
        val newBehavior = (record.behavior ?: EndpointBehavior(record.id)).let { b ->
            if (success) { b.successfulChecks++; b.observations++ } else { b.timeouts++; b.observations++ }
            if (latencyMs > 0) {
                b.minLatencyMs = if (b.minLatencyMs == null) latencyMs else minOf(b.minLatencyMs!!, latencyMs)
                b.maxLatencyMs = if (b.maxLatencyMs == null) latencyMs else maxOf(b.maxLatencyMs!!, latencyMs)
                b.totalLatencyMs += latencyMs
                b.typicalLatencyRange = "${b.minLatencyMs}–${b.maxLatencyMs} ms"
            }
            if (schema != null) {
                val prev = record.responseSchema
                if (prev != null && schema.shape != prev.shape) b.schemaChanges++
            }
            if (changedChain) b.redirectChanges++
            b.lastVerifiedMs = nowMs
            b
        }
        val currentSnap = Snapshot(
            id = record.id, url = normalized,
            host = hostOf(normalized),
            scheme = normalized.substringBefore("://", "http").lowercase(Locale.US),
            path = normalizePath("/" + normalized.substringAfter("://", "").substringAfter("/", "").substringBefore("?")),
            query = normalized.substringAfter("?", "").substringBefore("#").ifEmpty { "" }
        )
        val previousSnap = snapshots[record.id]
        val newDiff = if (previousSnap == null) UrlDiff(record.id, false, false, false, false, null, currentSnap)
            else UrlDiff(
                record.id,
                previousSnap.host != currentSnap.host,
                previousSnap.scheme != currentSnap.scheme,
                previousSnap.path != currentSnap.path,
                previousSnap.query != currentSnap.query,
                previousSnap, currentSnap
            )
        val updated = record.copy(
            url = normalized,
            evidence = record.evidence.copy(runtimeObserved = true),
            redirectChain = redirectChain,
            lastRedirectChain = record.redirectChain,
            responseSchema = schema ?: record.responseSchema,
            behavior = newBehavior,
            lastSeenMs = nowMs,
            lastDiff = newDiff
        )
        records[record.id] = updated
        indexUrl(updated)
        snapshots[record.id] = currentSnap
        return updated
    }

    /** Compare live response shape vs expected schema -> mismatch list (values never stored). */
    fun checkSchema(url: String, observed: Map<String, String>): UrlRecord? {
        val record = byUrl(UrlIntelligence.normalize(url)) ?: return null
        val expected = record.responseSchema ?: return record
        val mismatches = UrlIntelligence.schemaMismatches(expected.shape, observed)
        val newBehavior = (record.behavior ?: EndpointBehavior(record.id)).let { b ->
            if (mismatches.isNotEmpty()) b.schemaChanges++ else b
            b
        }
        val updated = record.copy(behavior = newBehavior)
        records[record.id] = updated
        return updated
    }

    /** Latest stored diff for an endpoint (computed on each [observe]). */
    fun diff(url: String): UrlDiff? = byUrl(UrlIntelligence.normalize(url))?.lastDiff

    /** Record an observation for an endpoint (used by GUI to mark verified / stale). */
    fun recordObservation(id: String, observed: Boolean = true) {
        val record = byId(id) ?: return
        val updated = record.copy(evidence = record.evidence.copy(runtimeObserved = observed))
        records[id] = updated
    }

    /** Full-snapshot change history: +new / -removed / ~changed (host/scheme/path/query/redirect/schema). */
    fun refreshHistory(currentIds: List<String>, now: Long = nowMs) {
        val prev = records.values.associate { it.url to it }
        for (id in currentIds) {
            if (!prev.containsKey(id)) {
                history += ChangeHistoryEntry(id, now, "ADDED")
            } else {
                val d = diff(id)
                if (d != null) {
                    val change = when {
                        d.hostChanged -> "HOST_CHANGED"
                        d.schemeChanged -> "SCHEME_CHANGED"
                        d.pathChanged -> "PATH_CHANGED"
                        d.queryChanged -> "QUERY_CHANGED"
                        else -> "CHANGED"
                    }
                    history += ChangeHistoryEntry(id, now, change)
                }
            }
        }
    }

    fun history(): List<ChangeHistoryEntry> = history.toList()

    // ---- dependency graph ----
    fun addEdge(from: String, to: String, type: RelationType): DependencyEdge {
        val e = DependencyEdge(from, to, type)
        edges += e
        return e
    }

    private val edges = ArrayList<DependencyEdge>()
    fun edges(): List<DependencyEdge> = edges.toList()

    fun dependents(id: String): List<UrlRecord> =
        edges.filter { it.fromId == id }.mapNotNull { records.values.find { r -> r.id == it.toId } }

    fun dependencies(id: String): List<UrlRecord> =
        edges.filter { it.toId == id }.mapNotNull { records.values.find { r -> r.id == it.fromId } }

    /** "78 channels offline" -> same source? same playlist? playlist request failed? => SOURCE-LEVEL FAILURE. */
    fun rootCause(channelsToPlaylist: Map<String, String>, failedPlaylistIds: Set<String>): String {
        if (channelsToPlaylist.isEmpty()) return "NO_CHANNELS"
        val playlists = channelsToPlaylist.values.toSet()
        val affected = playlists.intersect(failedPlaylistIds)
        return when {
            playlists.size == 1 && affected.size == 1 -> "SOURCE-LEVEL FAILURE"
            playlists.size > 1 && affected.size == playlists.size -> "MULTI-SOURCE FAILURE"
            playlists.size > 1 && affected.isNotEmpty() -> "PARTIAL SOURCE FAILURE"
            else -> "STREAM-LEVEL"
        }
    }

    fun filter(role: UrlRole?): List<UrlRecord> =
        if (role == null) records.values.toList() else records.values.filter { it.role == role }

    fun summary(): Map<String, Int> {
        val all = records.values
        return mapOf(
            "DISCOVERED" to all.size,
            "RUNTIME" to all.count { it.evidence.runtimeObserved },
            "STATIC_ONLY" to all.count { it.evidence.kind == EvidenceKind.STATIC_ONLY },
            "STREAMS" to all.count { it.role == UrlRole.STREAM || it.role == UrlRole.PLAYLIST },
            "CONFIG" to all.count { it.role == UrlRole.REMOTE_CONFIG || it.role == UrlRole.API },
            "UNKNOWN" to all.count { it.role == UrlRole.UNKNOWN }
        )
    }

    companion object {
        private val URL_RE = Regex("([a-z][a-z0-9+.-]*)://([^/?#]+)([^?#]*)(\\?[^#]*)?", RegexOption.IGNORE_CASE)

        fun hostOf(url: String): String {
            val m = URL_RE.find(url) ?: return ""
            var auth = m.groupValues[2]
            val at = auth.lastIndexOf('@')
            if (at >= 0) auth = auth.substring(at + 1) // strip userinfo
            val colon = auth.lastIndexOf(':')
            var host = if (colon >= 0) auth.substring(0, colon) else auth
            var port = if (colon >= 0) auth.substring(colon + 1) else ""
            host = host.lowercase(Locale.US)
            val scheme = m.groupValues[1].lowercase(Locale.US)
            if ((scheme == "http" && port == "80") || (scheme == "https" && port == "443")) port = ""
            return if (port.isEmpty()) host else "$host:$port"
        }

        fun protocolOf(url: String): String =
            (url.substringBefore("://", "").ifEmpty { "http" }).uppercase(Locale.US).let {
                if (it in setOf("WS", "WSS")) "WEBSOCKET" else it
            }

        fun classify(url: String): UrlRole {
            val lower = url.lowercase(Locale.US)
            val host = hostOf(lower)
            val p = lower.substringAfter("://", "").substringBefore("#")
            val pathOnly = p.substringAfter("/", "")            // everything after the host
            val lastSeg = pathOnly.substringBefore("?").substringAfterLast("/").ifEmpty { pathOnly.substringBefore("?") }
            val q = lower.substringAfter("?", "").substringBefore("#")
            return when {
                lower.startsWith("ws://") || lower.startsWith("wss://") -> UrlRole.WEBSOCKET
                "playlist" in host || lastSeg.endsWith(".m3u") -> UrlRole.PLAYLIST   // .m3u8 also ends with .m3u
                "epg" in host || lastSeg.contains("epg") -> UrlRole.EPG
                "config" in host -> UrlRole.REMOTE_CONFIG
                "ping" in host || "health" in host || "device" in host || lastSeg.contains("ping") -> UrlRole.DEVICE_HEALTH
                "auth" in host || "login" in host || q.contains("token") -> UrlRole.AUTH
                "notify" in host || "notification" in host -> UrlRole.NOTIFICATION
                lastSeg.endsWith(".png") || lastSeg.endsWith(".jpg") || lastSeg.endsWith(".jpeg") || lastSeg.endsWith(".webp") -> UrlRole.IMAGE
                "subtitle" in host || lastSeg.contains("vtt") || lastSeg.contains("srt") -> UrlRole.SUBTITLE
                lastSeg.endsWith(".json") && "config" in pathOnly -> UrlRole.REMOTE_CONFIG
                "api" in host || pathOnly.startsWith("v1/") || pathOnly.startsWith("v2/") -> UrlRole.API
                "analytics" in host || "telemetry" in host || "firebase" in host || "crash" in host -> UrlRole.ANALYTICS
                lastSeg.endsWith(".m3u8") || lastSeg.endsWith(".mp4") || lastSeg.endsWith(".ts") || lastSeg.endsWith(".mpd") -> UrlRole.STREAM
                "cdn" in host -> UrlRole.CDN
                else -> UrlRole.UNKNOWN
            }
        }

        fun normalize(url: String): String {
            val m = URL_RE.find(url) ?: return url
            val scheme = m.groupValues[1].lowercase(Locale.US)
            var auth = m.groupValues[2]
            val at = auth.lastIndexOf('@')
            if (at >= 0) auth = auth.substring(at + 1)
            val colon = auth.lastIndexOf(':')
            var host = if (colon >= 0) auth.substring(0, colon) else auth
            var port = if (colon >= 0) auth.substring(colon + 1) else ""
            host = host.lowercase(Locale.US)
            if ((scheme == "http" && port == "80") || (scheme == "https" && port == "443")) port = ""
            val normHost = if (port.isEmpty()) host else "$host:$port"
            val path = normalizePath(m.groupValues[3])
            val query = m.groupValues[4]
            val normQuery = if (query.isEmpty()) "" else query
            return "$scheme://$normHost$path$normQuery"
        }

        private fun normalizePath(p: String): String =
            p.replace(Regex("//+"), "/").ifEmpty { "" }

        fun snapshotId(url: String): String = url

        fun snapshot(url: String, id: String): Snapshot {
            val m = URL_RE.find(url)
            val scheme = m?.groupValues?.get(1)?.lowercase(Locale.US) ?: "http"
            val host = hostOf(url)
            val path = normalizePath(m?.groupValues?.get(3) ?: "")
            val query = m?.groupValues?.get(4) ?: ""
            return Snapshot(id = id, url = url, host = host, scheme = scheme, path = path, query = query)
        }

        fun diff(previous: Snapshot?, current: Snapshot?): UrlDiff? {
            if (current == null) return null
            val prev = previous
            if (prev == null) return UrlDiff(current.id, false, false, false, false, null, current)
            return UrlDiff(
                id = current.id,
                hostChanged = prev.host != current.host,
                schemeChanged = prev.scheme != current.scheme,
                pathChanged = prev.path != current.path,
                queryChanged = prev.query != current.query,
                previous = prev,
                current = current
            )
        }

        fun schemaMismatches(expected: Map<String, String>, observed: Map<String, String>): List<String> {
            val out = ArrayList<String>()
            for ((k, v) in expected) {
                val o = observed[k]
                if (o == null) out += "missing:$k"
                else if (o != v) out += "$k: expected=$v received=$o"
            }
            return out
        }

        /** Redact credential-bearing query params + Authorization/Cookie headers. */
        fun sanitizeUrl(url: String): String {
            val qIdx = url.indexOf('?')
            if (qIdx < 0) return url
            val base = url.substring(0, qIdx + 1)
            val query = url.substring(qIdx + 1)
            val sensitive = setOf("token", "apikey", "api_key", "key", "auth", "password", "pwd", "secret", "client_secret", "access_token", "refresh_token", "sig", "signature", "session", "cookie")
            val parts = query.split("&").map { kv ->
                val eq = kv.indexOf('=')
                val k = (if (eq >= 0) kv.substring(0, eq) else kv).lowercase(Locale.US)
                if (k in sensitive) (if (eq >= 0) kv.substring(0, eq + 1) else kv) + "•••"
                else kv
            }
            return base + parts.joinToString("&")
        }

        fun sanitizeHeader(name: String, value: String): String =
            if (name.equals("Authorization", true) || name.equals("Cookie", true)) "•••" else value

        fun formatTime(tsMs: Long): String =
            SimpleDateFormat("HH:mm", Locale.US).format(Date(tsMs))
    }
}
