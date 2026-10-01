package com.novastream.tv

/**
 * JVM-only tests for the pure memory core (no Android needed).
 * Run with the repo's kotlinc: see tdd/MemoryCoreTest.kt header.
 */
fun main() {
    // 1. Envelope validation — no prose bypass
    val bad = MemoryItem("mem_x", MemoryType.ADVICE, "STREAM", "t1", MemoryState.VERIFIED, 1.7, 1, 0, 100, 50, 100, SyncScope.LOCAL_ONLY, detail = "ai prose")
    val v = MemoryEnvelopeValidator().validate(bad)
    check(!v.ok()) { "invalid envelope must be rejected" }
    check(v.errors.any { it.contains("confidence") }) { "confidence range enforced" }

    val good = MemoryItem("mem_y", MemoryType.STREAM_PATTERN, "STREAM", "t1", MemoryState.VERIFIED, 0.84, 31, 5, 1000, 2000, 2000, SyncScope.AGGREGATED)
    check(MemoryEnvelopeValidator().validate(good).ok()) { "valid envelope passes" }

    // 2. Log-line round trip
    check(MemoryItem.fromLogLine(good.toLogLine()) == good) { "round trip" }

    // 3. Promotion: private -> collective, only with evidence
    check(promoteToKnowledge(good.copy(syncScope = SyncScope.LOCAL_ONLY)) == null) { "LOCAL_ONLY never promoted" }
    check(promoteToKnowledge(good.copy(supportCount = 5)) == null) { "below threshold not promoted" }
    val promoted = promoteToKnowledge(good)!!
    check(promoted.syncScope == SyncScope.PUBLIC_KNOWLEDGE && promoted.state == MemoryState.VERIFIED) { "promotion" }

    // 4. Buffer: insignificant windows are dropped, significant ones persist
    val buffer = MemoryBuffer(windowMs = 1000, persistThresholdRebuffers = 2, persistThresholdBufferMs = 500)
    buffer.addSample(100, rebuffer = false, bufferMs = 100)
    buffer.closeWindow(1500)
    check(buffer.closeWindow(1500) == null) { "no second window" }

    val b2 = MemoryBuffer(windowMs = 1000, persistThresholdRebuffers = 2, persistThresholdBufferMs = 500)
    b2.addSample(100, rebuffer = true, bufferMs = 300)
    b2.addSample(200, rebuffer = true, bufferMs = 300)
    val sig = b2.closeWindow(1500)
    check(sig != null && sig.contains("rebuffers=2")) { "significant window persists: $sig" }

    // 5. Policy: playlists can never change network/security
    check(evaluatePolicy("playlist", "change proxy") is PolicyOutcome.Deny) { "playlist proxy denied" }
    check(evaluatePolicy("playlist", "add channels") is PolicyOutcome.Allow) { "playlist benign allowed" }
    check(evaluatePolicy("source", "change dns") is PolicyOutcome.RequestApproval) { "source network op needs approval" }
    check(evaluatePolicy("user", "update stream url") is PolicyOutcome.Allow) { "user benign auto" }

    // 6. Knowledge manifest: sha256 + version gate
    val payload = "{\"knowledge\":[{\"id\":\"k1\"}]}"
    val manifest = KnowledgeManifest("1.0.0", 1, "2026-10-01", sha256Hex(payload), "1.0.0")
    check(KnowledgeManifest.verifyPayload(manifest, payload, "2.0")) { "valid manifest verifies" }
    check(!KnowledgeManifest.verifyPayload(manifest, payload + " ", "2.0")) { "tampered payload rejected" }
    check(!KnowledgeManifest.verifyPayload(manifest, payload, "0.9")) { "old app rejected" }
    check(!KnowledgeManifest.verifyPayload(manifest.copy(schemaVersion = 9), payload, "2.0")) { "newer schema rejected" }

    // 7. canShare rules
    check(!good.copy(syncScope = SyncScope.LOCAL_ONLY).canShare) { "local-only never shares" }
    check(good.copy(syncScope = SyncScope.AGGREGATED).canShare) { "aggregated shares" }
    check(!good.copy(syncScope = SyncScope.PUBLIC_KNOWLEDGE, state = MemoryState.HYPOTHESIS).canShare) { "unverified hypothesis never shares" }

    println("Memory core tests passed")
}
