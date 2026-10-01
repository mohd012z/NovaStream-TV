package com.novastream.tv

import java.io.File

/**
 * Level-1 (device) memory repository — the single gateway for all memory
 * access. Lola, the resolver, the GUI and the AI layer must read memory
 * through this class, never directly from files (Anam, 2026-10-01).
 *
 * File-backed on purpose: zero new dependencies, inspectable on device, and
 * a transparent future Room migration (one class changes, no callers do).
 * Append-only log + bounded retention; raw events are pruned by age.
 */
class MemoryRepository(baseDir: File, val buffer: MemoryBuffer = MemoryBuffer()) {
    private val dir = baseDir.apply { mkdirs() }
    private val memoryFile = File(dir, "memory.log")
    private val activityFile = File(dir, "activity.log")
    private val eventsFile = File(dir, "events.log")
    private val retention = RetentionPolicy()

    data class MemoryQuery(val targetId: String? = null, val type: MemoryType? = null, val limit: Int = 50)
    data class MemoryMutation(
        val type: MemoryType,
        val scope: String,
        val targetId: String,
        val detail: String,
        val syncScope: SyncScope,
        val confidence: Double,
        val supportCount: Int = 1,
        val contradictionCount: Int = 0,
        val state: MemoryState = MemoryState.HYPOTHESIS,
        val nowMs: Long = System.currentTimeMillis()
    )
    data class MutationResult(val accepted: Boolean, val outcome: PolicyOutcome, val item: MemoryItem?, val reason: String)

    // ---------- write path (observe) ----------

    /** Every significant event flows through here; nothing else writes memory. */
    fun observe(event: NovaEvent) {
        appendLine(eventsFile, "${event.atMs}|${event.kind}|${event.targetId}|${event.detail}")
        buffer.addEvent(event.atMs, "${event.kind}: ${event.detail}")
        if (event.persistAsMemory) {
            record(
                MemoryMutation(
                    type = event.type,
                    scope = event.scope,
                    targetId = event.targetId,
                    detail = event.detail,
                    syncScope = event.syncScope,
                    confidence = event.confidence,
                    state = MemoryState.VERIFIED,
                    nowMs = event.atMs
                )
            )
        }
    }

    /** Persist a memory item after envelope validation (no prose bypass). */
    fun record(mutation: MemoryMutation): MutationResult {
        val item = MemoryItem(
            id = "mem_${System.currentTimeMillis().toString(36)}${(0..9999).random().toString(36)}",
            type = mutation.type,
            scope = mutation.scope,
            targetId = mutation.targetId,
            state = mutation.state,
            confidence = mutation.confidence,
            supportCount = mutation.supportCount,
            contradictionCount = mutation.contradictionCount,
            firstSeen = mutation.nowMs,
            lastSeen = mutation.nowMs,
            lastVerified = if (mutation.state == MemoryState.VERIFIED) mutation.nowMs else null,
            syncScope = mutation.syncScope,
            detail = mutation.detail
        )
        val validator = MemoryEnvelopeValidator().validate(item)
        if (!validator.ok()) {
            return MutationResult(false, PolicyOutcome.Deny(), null, "invalid envelope: ${validator.errors.joinToString(", ")}")
        }
        appendLine(memoryFile, item.toLogLine())
        return MutationResult(true, PolicyOutcome.Allow(autoCompleted = true), item, "recorded")
    }

    /** Close the metric window; persists only when significant (thresholds). */
    fun flushWindow(targetId: String = "player", nowMs: Long = System.currentTimeMillis()): Boolean {
        val label = buffer.closeWindow(nowMs) ?: return false
        return record(
            MemoryMutation(
                type = MemoryType.HEALTH_METRIC,
                scope = "STREAM",
                targetId = targetId,
                detail = label,
                syncScope = SyncScope.AGGREGATED,
                confidence = 0.5,
                nowMs = nowMs
            )
        ).accepted
    }

    // ---------- read path (one API for Lola / Kernel / in_ai / Resolver / GUI) ----------

    fun recent(query: MemoryQuery = MemoryQuery()): List<MemoryItem> =
        readItems().filter { query.targetId == null || it.targetId == query.targetId }
            .filter { query.type == null || it.type == query.type }
            .sortedByDescending { it.lastSeen }
            .take(query.limit)

    fun patterns(targetId: String): List<MemoryItem> =
        recent(MemoryQuery(targetId = targetId, type = MemoryType.STREAM_PATTERN, limit = 100))

    fun evidence(targetId: String): List<MemoryItem> =
        recent(MemoryQuery(targetId = targetId, limit = 100))

    fun propose(mutation: MemoryMutation): MutationResult = record(mutation)

    fun activityEvents(sinceMs: Long = 0L): List<String> =
        buffer.events(sinceMs).map { "${it.first}|${it.second}" } + readLines(eventsFile).takeLast(200)

    fun storageStats(): StorageStats {
        fun size(f: File): Long = if (f.exists()) f.length() else 0L
        val total = size(memoryFile) + size(activityFile) + size(eventsFile)
        val items = readItems()
        return StorageStats(
            databaseBytes = total,
            eventsBytes = size(eventsFile),
            patternsBytes = items.count { it.type == MemoryType.STREAM_PATTERN }.toLong() * 64L,
            healthBytes = items.count { it.type == MemoryType.HEALTH_METRIC }.toLong() * 64L,
            otherBytes = (total - size(eventsFile)).coerceAtLeast(0L),
            itemCounts = items.groupBy { it.type }.mapValues { it.value.size }
        )
    }

    /** Retention: raw events 7d, health 90d, patterns persistent. */
    fun applyRetention(nowMs: Long = System.currentTimeMillis()): Int {
        var pruned = 0
        val kept = readLines(eventsFile).filter { line ->
            val ts = line.substringBefore('|').toLongOrNull() ?: return@filter false
            if (nowMs - ts > retention.rawEventsMs) { pruned += 1; false } else true
        }
        rewriteIfChanged(eventsFile, kept)
        val memKept = readLines(memoryFile).filter { line ->
            MemoryItem.fromLogLine(line)?.let { item ->
                if (item.type == MemoryType.HEALTH_METRIC && nowMs - item.lastSeen > retention.healthHistoryMs) {
                    pruned += 1
                    false
                } else true
            } ?: true
        }
        rewriteIfChanged(memoryFile, memKept)
        return pruned
    }

    /** Sanitized export (Control -> AI -> Memory -> Storage -> EXPORT). */
    fun sanitizedExport(includeRawEvents: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("{\"schema\":\"novastream-memory-export/1\",\"exportedAt\":${System.currentTimeMillis()}}")
        sb.appendLine("items:")
        readItems().take(2000).forEach { sb.appendLine(it.toLogLine()) }
        if (includeRawEvents) readLines(eventsFile).forEach { sb.appendLine("event:" + it) }
        return sb.toString()
    }

    fun cleanCache() {
        buffer.closeWindow(System.currentTimeMillis())
        applyRetention()
    }

    // ---------- internals ----------

    private fun readItems(): List<MemoryItem> =
        readLines(memoryFile).mapNotNull { MemoryItem.fromLogLine(it) }

    private fun readLines(f: File): List<String> =
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()

    private fun appendLine(f: File, line: String) {
        runCatching { f.appendText(line + "\n") }
    }

    private fun rewriteIfChanged(f: File, lines: List<String>) {
        if (lines.size == readLines(f).size) return
        runCatching { f.writeText(lines.joinToString("\n") { it } + "\n") }
    }
}

data class StorageStats(
    val databaseBytes: Long,
    val eventsBytes: Long,
    val patternsBytes: Long,
    val healthBytes: Long,
    val otherBytes: Long,
    val itemCounts: Map<MemoryType, Int>
) {
    fun human(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

/** A significant, nameable event entering the memory gateway. */
data class NovaEvent(
    val atMs: Long = System.currentTimeMillis(),
    val kind: String,
    val targetId: String,
    val detail: String,
    val persistAsMemory: Boolean = false,
    val type: MemoryType = MemoryType.OBSERVATION,
    val scope: String = "STREAM",
    val syncScope: SyncScope = SyncScope.LOCAL_ONLY,
    val confidence: Double = 1.0
)
