package com.novastream.tv

/**
 * Pure-Kotlin core of NovaStreamer's hybrid memory architecture.
 *
 * Design (Anam, 2026-10-01):
 *  - Level 1 DEVICE  : realtime + private memory (this store)
 *  - Level 2 DATABASE: optional cross-device aggregated learning (later)
 *  - Level 3 GITHUB  : signed/versioned knowledge only (KnowledgeManifest)
 *
 * The core has zero Android/JSON dependencies so it is unit-testable on the
 * JVM. "No AI prose may bypass this schema" — every durable memory item is a
 * validated [MemoryItem] envelope.
 */
enum class MemoryType {
    OBSERVATION, STREAM_PATTERN, HEALTH_METRIC, SESSION, HYPOTHESIS,
    KNOWLEDGE, ADVICE, OUTCOME, FEEDBACK
}

/** Whether an item may leave the device. Credentials are NEVER a memory type. */
enum class SyncScope {
    LOCAL_ONLY,       // current session, private preference
    PRIVATE_SYNC,     // user-level, later via authenticated backend
    AGGREGATED,       // anonymized metrics, cross-device
    PUBLIC_KNOWLEDGE  // promoted, verified, shipped in signed knowledge releases
}

/** State machine: hypothesis -> verified / rejected. AI prose stays out. */
enum class MemoryState {
    HYPOTHESIS,
    VERIFIED,
    REJECTED,
    SUPERSEDED
}

data class MemoryItem(
    val id: String,
    val type: MemoryType,
    val scope: String,
    val targetId: String,
    val state: MemoryState,
    val confidence: Double,
    val supportCount: Int,
    val contradictionCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastVerified: Long?,
    val syncScope: SyncScope,
    val schemaVersion: Int = MEMORY_SCHEMA_VERSION,
    val detail: String = ""
) {
    /** One line per item — append-only event log format.
     *  Confidence is fixed-format, locale-explicit so a Double round-trips
     *  exactly on any JVM locale (no `0.84` vs `0,84` surprises). */
    fun toLogLine(): String =
        "$id|${type.name}|$scope|$targetId|${state.name}|" +
        java.text.DecimalFormat("#.##########", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US)).format(confidence) +
        "|$supportCount|$contradictionCount|$firstSeen|$lastSeen|${lastVerified ?: ""}|" +
        "${syncScope.name}|$schemaVersion|$detail"

    companion object {
        fun fromLogLine(line: String): MemoryItem? = runCatching {
            val p = line.split("|")
            if (p.size < 14) return null
            MemoryItem(
                id = p[0],
                type = MemoryType.valueOf(p[1]),
                scope = p[2],
                targetId = p[3],
                state = MemoryState.valueOf(p[4]),
                confidence = p[5].toDouble(),
                supportCount = p[6].toInt(),
                contradictionCount = p[7].toInt(),
                firstSeen = p[8].toLong(),
                lastSeen = p[9].toLong(),
                lastVerified = p[10].takeIf { it.isNotEmpty() }?.toLong(),
                syncScope = SyncScope.valueOf(p[11]),
                schemaVersion = p[12].toInt(),
                detail = p.getOrNull(13).orEmpty()
            )
        }.getOrNull()
    }
}

const val MEMORY_SCHEMA_VERSION = 1

/** Anonymization is mandatory before anything may cross LOCAL_ONLY. */
val MemoryItem.canShare: Boolean
    get() = when (syncScope) {
        SyncScope.PRIVATE_SYNC -> true
        SyncScope.AGGREGATED -> true
        SyncScope.PUBLIC_KNOWLEDGE -> state == MemoryState.VERIFIED
        SyncScope.LOCAL_ONLY -> false
    }

/**
 * Private -> collective rule: an anonymous stream-health signal becomes
 * shared knowledge only after enough independent observations.
 */
fun promoteToKnowledge(item: MemoryItem, minSupport: Int = 27): MemoryItem? {
    if (item.syncScope == SyncScope.LOCAL_ONLY || item.syncScope == SyncScope.PRIVATE_SYNC) return null
    if (item.supportCount < minSupport) return null
    if (item.contradictionCount * 3 > item.supportCount) return null // >25% contradictory
    return item.copy(
        state = MemoryState.VERIFIED,
        syncScope = SyncScope.PUBLIC_KNOWLEDGE,
        lastVerified = item.lastSeen
    )
}

class MemoryEnvelopeValidator {
    val errors = mutableListOf<String>()

    fun validate(item: MemoryItem) = apply {
        if (item.id.isBlank()) errors += "id is blank"
        if (item.targetId.isBlank()) errors += "targetId is blank"
        if (item.confidence !in 0.0..1.0) errors += "confidence out of [0,1]: ${item.confidence}"
        if (item.supportCount < 0) errors += "negative supportCount"
        if (item.contradictionCount < 0) errors += "negative contradictionCount"
        if (item.lastSeen < item.firstSeen) errors += "lastSeen before firstSeen"
        if (item.state == MemoryState.VERIFIED && item.lastVerified == null)
            errors += "verified item has no lastVerified"
        if (item.state == MemoryState.VERIFIED && item.syncScope == SyncScope.LOCAL_ONLY)
            errors += "verified item cannot be LOCAL_ONLY (it is shareable by nature)"
    }

    fun ok(): Boolean = errors.isEmpty()
}

/**
 * Bounded in-memory buffer: player metrics aggregate per window; only
 * significant events persist. Prevents the DB from growing by the second.
 */
class MemoryBuffer(
    val windowMs: Long = 30_000L,
    val persistThresholdRebuffers: Int = 2,
    val persistThresholdBufferMs: Long = 1500L
) {
    private data class Window(
        val startMs: Long,
        var samples: Int = 0,
        var rebuffers: Int = 0,
        var totalBufferMs: Long = 0,
        var maxRebufferMs: Long = 0
    )

    private var window: Window? = null
    private val events = mutableListOf<Pair<Long, String>>()
    @Volatile var lastSignificantAt: Long = 0L
        private set

    fun addSample(nowMs: Long, rebuffer: Boolean, bufferMs: Long) {
        val w = window?.takeIf { it.startMs + windowMs > nowMs } ?: Window(nowMs).also { window = it }
        w.samples += 1
        if (rebuffer) {
            w.rebuffers += 1
            w.totalBufferMs += bufferMs
            w.maxRebufferMs = maxOf(w.maxRebufferMs, bufferMs)
        }
    }

    /** Add a significant event (stream switch, recovery decision, block). */
    fun addEvent(nowMs: Long, label: String) {
        if (events.size >= 512) events.removeAt(0)
        events.add(nowMs to label)
        lastSignificantAt = nowMs
    }

    /** Close the current window; returns a persistable event label or null. */
    fun closeWindow(nowMs: Long): String? {
        val w = window ?: return null
        window = null
        return if (w.rebuffers >= persistThresholdRebuffers || w.totalBufferMs >= persistThresholdBufferMs) {
            "buffer:rebuffers=${w.rebuffers},totalMs=${w.totalBufferMs},maxMs=${w.maxRebufferMs},samples=${w.samples}"
        } else null
    }

    fun events(sinceMs: Long): List<Pair<Long, String>> = events.filter { it.first >= sinceMs }
}

/** Retention policy per memory class (Anam's Storage GUI numbers). */
data class RetentionPolicy(
    val rawEventsMs: Long = 7L * 24 * 3600 * 1000,          // 7 days
    val healthHistoryMs: Long = 90L * 24 * 3600 * 1000,     // 90 days
    val patternsMs: Long = Long.MAX_VALUE                    // automatic/persistent
)

/**
 * Versioned knowledge manifest — the ONLY thing the app accepts from GitHub.
 * Verified before use: schema version, app compatibility, SHA-256, then the
 * payload loads; on failure the previous version stays active (ROLLBACK).
 */
data class KnowledgeManifest(
    val knowledgeVersion: String,
    val schemaVersion: Int,
    val created: String,
    val sha256: String,
    val minimumAppVersion: String
) {
    companion object {
        fun verifyPayload(manifest: KnowledgeManifest, payload: String, appVersion: String): Boolean {
            if (manifest.schemaVersion > MEMORY_SCHEMA_VERSION) return false
            if (appVersion < manifest.minimumAppVersion) return false
            return sha256Hex(payload) == manifest.sha256.lowercase()
        }
    }
}

/** FNV-1a 64-bit + hex — deterministic, dependency-free. */
fun sha256Hex(data: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(data.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Deterministic policy evaluation (mirrors 43rm35 Lola risk classes so the
 * app's decision surface stays evidence-first).
 */
sealed class PolicyOutcome(val decision: String, val reason: String) {
    class Allow(val autoCompleted: Boolean) : PolicyOutcome("ALLOW", if (autoCompleted) "AUTO-COMPLETED" else "allowed")
    class Deny : PolicyOutcome("DENIED", "playlist metadata cannot change NovaStreamer network settings")
    class RequestApproval(val impact: String) : PolicyOutcome("APPROVAL_REQUIRED", impact)
}

fun evaluatePolicy(origin: String, operation: String): PolicyOutcome {
    val op = operation.lowercase()
    val networkOp = op.contains("proxy") || op.contains("dns") || op.contains("network") || op.contains("security") || op.contains("credential")
    return when {
        origin == "playlist" && networkOp -> PolicyOutcome.Deny()
        origin == "playlist" -> PolicyOutcome.Allow(autoCompleted = true)
        origin == "source" && networkOp -> PolicyOutcome.RequestApproval("Change affects all stream traffic")
        networkOp -> PolicyOutcome.RequestApproval("Network/security change requires review")
        else -> PolicyOutcome.Allow(autoCompleted = true)
    }
}
