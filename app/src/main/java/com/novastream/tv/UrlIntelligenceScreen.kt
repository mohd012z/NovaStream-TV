package com.novastream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Control → Detection → URL Intelligence.
 *
 * "Not just collecting addresses, but understanding which subsystem owns each
 * endpoint, what triggers it, what it returns, what depends on it, whether it
 * was actually observed, how its behavior changes over time, and what evidence
 * supports every conclusion." (Anam, 2026-10-01)
 *
 * Trust: the copy menu always sanitizes credentials; nothing here is a
 * credential collector.
 */
private val UiAccent = Color(0xFF67D6FF)
private val UiPanel = Color(0xFF121924)
private val UiPanel2 = Color(0xFF1A2633)
private val UiMuted = Color(0xFFA8B3C0)
private val UiGreen = Color(0xFF34D399)
private val UiAmber = Color(0xFFFBBF24)
private val UiRed = Color(0xFFFB7185)

private val COPY_OPTIONS = listOf(
    "Host only", "URL", "Method", "Call site", "Dependency map", "Sanitized diagnostic", "JSON record"
)
private val COPY_KEYS = listOf("HOST", "URL", "METHOD", "CALL_SITE", "DEP_MAP", "SANITIZED", "JSON")

@Composable
fun UrlIntelligenceScreen(
    engine: UrlIntelligence,
    repo: MemoryRepository,
    sources: List<PlaylistSource>,
    playlist: List<PlaylistItem>
) {
    // Seed the engine from live app context (playlist source + stream URLs).
    var seeded by remember { mutableStateOf(false) }
    if (!seeded) {
        seeded = true
        engine.nowMs = System.currentTimeMillis()
        sources.forEach { s ->
            val role = if (s.url.endsWith(".m3u", true) || s.url.endsWith(".m3u8", true)) UrlRole.PLAYLIST else UrlIntelligence.classify(s.url)
            engine.register(s.url, role = role, source = DiscoverySurface.PLAYLIST, runtimeObserved = s.healthy,
                trigger = "PLAYLIST_REFRESH", outputConsumer = "Channel[]",
                location = Location("novastream", "app code", cls = "PlaylistSourceStore", method = "refresh()", line = null))
        }
        playlist.filter { it.kind == MediaKind.LIVE || it.kind == MediaKind.UNKNOWN }.take(200).forEach { item ->
            engine.register(item.streamUrl, source = DiscoverySurface.M3U_ATTR, runtimeObserved = false,
                trigger = "CHANNEL_SELECT", outputConsumer = "Media3",
                caller = "StreamResolver.resolve()")
        }
        repo.observe(NovaEvent(kind = "URL_INTELLIGENCE", targetId = "scan", detail = "discovered=${engine.records.size}"))
    }

    var selected by remember { mutableStateOf<UrlRecord?>(null) }
    var filterRole by remember { mutableStateOf<UrlRole?>(null) }
    var copyOpen by remember { mutableStateOf(false) }

    if (selected != null) {
        EndpointInspector(
            record = selected!!,
            engine = engine,
            onBack = { selected = null },
            onCopy = { copyOpen = true }
        )
        if (copyOpen) {
            CopyMenu(record = selected!!, onDismiss = { copyOpen = false }, repo = repo)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Dns, null, tint = UiAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("URL Intelligence", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { /* parent back handled by Control */ }) { Text("Detection", color = UiMuted, fontSize = 12.sp) }
            }
        }

        // Summary
        val s = engine.summary()
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UiPanel).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    UiStat("${s["DISCOVERED"]}", "Discovered")
                    UiStat("${s["RUNTIME"]}", "Runtime observed")
                    UiStat("${s["STATIC_ONLY"]}", "Static only", UiAmber)
                    UiStat("${s["STREAMS"]}", "Streams")
                    UiStat("${s["CONFIG"]}", "Config/API")
                    UiStat("${s["UNKNOWN"]}", "Unknown", UiMuted)
                }
            }
        }

        // Filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf<UrlRole?>(null, UrlRole.STREAM, UrlRole.PLAYLIST, UrlRole.REMOTE_CONFIG, UrlRole.API, UrlRole.EPG, UrlRole.WEBSOCKET, UrlRole.UNKNOWN)
                chips.forEach { r ->
                    val active = filterRole == r
                    val label = r?.name ?: "ALL"
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) UiAccent else UiPanel2)
                            .clickable { filterRole = r }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(label, color = if (active) Color.Black else UiMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Change-detection banner (first record with a real diff)
        val changed = engine.records.values.firstOrNull { it.lastDiff?.let { d -> d.hostChanged || d.schemeChanged || d.pathChanged || d.queryChanged } == true }
        if (changed != null) {
            item {
                val d = changed.lastDiff!!
                UiPanelBox(UiAmber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = UiAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("URL CHANGE DETECTED", color = UiAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    UiKV("Source", changed.host)
                    UiKV("Previous", d.previous?.let { it.scheme + "://" + it.host + it.path } ?: "—")
                    UiKV("Current", d.current?.let { it.scheme + "://" + it.host + it.path } ?: "—")
                    val what = listOfNotNull(
                        if (d.hostChanged) "hostname" else null,
                        if (d.schemeChanged) "scheme" else null,
                        if (d.pathChanged) "path" else null,
                        if (d.queryChanged) "query" else null
                    ).joinToString(", ")
                    UiKV("Risk", "Needs verification")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SmallButton("ANALYZE") { selected = changed }
                        SmallButton("HISTORY") { selected = changed }
                        SmallButton("COPY") { copyOpen = true; selected = changed }
                    }
                }
            }
        }

        // Endpoint list
        val list = engine.filter(filterRole)
        item {
            Text("${list.size} endpoint${if (list.size == 1) "" else "s"}", color = UiMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        items(list, key = { it.id }) { rec ->
            EndpointRow(rec) { selected = rec }
        }
    }
}

// ---- Endpoint list row ----
@Composable
private fun EndpointRow(rec: UrlRecord, onClick: () -> Unit) {
    val ev = rec.evidence
    val dot = when (ev.kind) {
        EvidenceKind.STATIC_AND_RUNTIME -> UiGreen
        EvidenceKind.RUNTIME_ONLY -> UiGreen
        EvidenceKind.STATIC_ONLY -> UiAmber
        EvidenceKind.INFERRED -> UiAccent
        EvidenceKind.STALE -> UiMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UiPanel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rec.host, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rec.role.name, color = dot, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${rec.protocol} • ${rec.method ?: "GET"}", color = UiMuted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (ev.runtimeObserved) "Runtime verified" else if (ev.staticFound) "Static only" else ev.kind.name, color = UiMuted, fontSize = 11.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rec.copy("HOST"), color = UiMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("[ INFO ]", color = UiAccent, fontSize = 11.sp)
        }
    }
}

// ---- Endpoint Inspector (detail view) ----
@Composable
private fun EndpointInspector(record: UrlRecord, engine: UrlIntelligence, onBack: () -> Unit, onCopy: () -> Unit) {
    val behavior = record.behavior
    val deps = engine.dependents(record.id)
    val dependants = engine.dependencies(record.id)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text("← URL Intelligence", color = UiAccent, fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("ENDPOINT INSPECTOR", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UiSection("Role / origin")
                UiKV("Role", record.role.name)
                UiKV("Host", record.host)
                UiKV("Protocol", record.protocol)
                UiKV("Method", record.method ?: "GET")
                val locs = record.locations
                UiKV("Found", if (locs.isEmpty()) record.source.name
                    else locs.joinToString(" + ") { it.container } + if (record.evidence.runtimeObserved) " + Runtime" else "")
                UiKV("Caller", record.caller ?: "unresolved")
                UiKV("Triggered", record.trigger ?: "—")
                UiKV("Response", record.responseContentType ?: record.responseSchema?.endpointType?.name ?: "—")
                UiKV("Controls", record.outputConsumer ?: "—")
                record.responseSchema?.let { UiKV("Schema", it.describe()) }
            }
        }

        // Forensic provenance / location hierarchy
        if (record.locations.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel).padding(14.dp)) {
                    UiSection("Location (forensic)")
                    record.locations.forEach { loc ->
                        loc.hierarchy().forEachIndexed { i, line ->
                            Row {
                                Spacer(Modifier.width((i * 14).dp))
                                Text((if (i == 0) "" else "└─ ") + line, color = if (i == 0) Color.White else UiMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("String presence is a fact. Program use is an inference until the xref / call site resolves.", color = UiMuted, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                }
            }
        }

        // Redirect chain
        if (record.redirectChain.size > 1) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel).padding(14.dp)) {
                    UiSection("Redirect chain")
                    record.redirectChain.forEachIndexed { i, hop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$(i + 1)", color = UiAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(hop, color = if (i == record.redirectChain.lastIndex) Color.White else UiMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Dependencies
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel).padding(14.dp)) {
                UiSection("Dependencies")
                if (deps.isEmpty() && dependants.isEmpty()) {
                    Text("No mapped dependencies.", color = UiMuted, fontSize = 12.sp)
                }
                if (deps.isNotEmpty()) {
                    Text("Upstream (feeds this endpoint):", color = UiMuted, fontSize = 11.sp)
                    deps.forEach { DepLine(it) }
                }
                if (dependants.isNotEmpty()) {
                    Text("Downstream (depends on this):", color = UiMuted, fontSize = 11.sp)
                    dependants.forEach { DepLine(it) }
                }
            }
        }

        // Health
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel).padding(14.dp)) {
                UiSection("Health")
                if (behavior == null) {
                    Text("No runtime health yet (static only).", color = UiMuted, fontSize = 12.sp)
                } else {
                    UiKV("Successful checks", behavior.successfulChecks.toString())
                    UiKV("Timeouts", behavior.timeouts.toString())
                    UiKV("Schema changes", behavior.schemaChanges.toString())
                    UiKV("Redirect changes", behavior.redirectChanges.toString())
                    UiKV("Typical latency", behavior.typicalLatencyRange)
                    UiKV("Last verified", UrlIntelligence.formatTime(behavior.lastVerifiedMs))
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SmallButton("COPY") { onCopy() }
                SmallButton("HISTORY") { /* engine.history() */ }
                SmallButton("DEPENDENCY MAP") { /* open graph */ }
                SmallButton("HELP") { /* tooltip */ }
            }
        }
    }
}

@Composable
private fun DepLine(rec: UrlRecord) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(UiAccent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("${rec.role.name} · ${rec.host}", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Copy menu (always sanitized) ----
@Composable
private fun CopyMenu(record: UrlRecord, onDismiss: () -> Unit, repo: MemoryRepository) {
    var picked by remember { mutableStateOf(5) } // Sanitized diagnostic
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(16.dp)).background(UiPanel).padding(18.dp)) {
            Text("COPY", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(record.host, color = UiMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            COPY_OPTIONS.forEachIndexed { i, opt ->
                Row(Modifier.fillMaxWidth().clickable { picked = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).border(1.dp, if (picked == i) UiAccent else UiMuted, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(opt, color = if (picked == i) Color.White else UiMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (picked == i) Text("●", color = UiAccent, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val preview = when (COPY_KEYS[picked]) {
                "DEP_MAP" -> (record.id + " → " + "dependency map")
                else -> record.copy(COPY_KEYS[picked]).let {
                    if (it.length > 48) it.take(48) + "…" else it
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(UiPanel2).padding(10.dp)) {
                Text("Preview (sanitized)", color = UiMuted, fontSize = 11.sp)
                Text(preview, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SmallButton("CANCEL") { onDismiss() }
                Spacer(Modifier.width(8.dp))
                val payload = if (picked == 4) (record.id + " → dependency map") else record.copy(COPY_KEYS[picked])
                SmallButton("COPY") {
                    // Clipboard write is an Android-context call; log the sanitized payload.
                    repo.observe(NovaEvent(kind = "URL_COPY", targetId = record.id, detail = "copied=${COPY_KEYS[picked]} sanitized=true preview=${payload.take(40)}"))
                    onDismiss()
                }
            }
        }
    }
}

// ---- small building blocks (UI-local to avoid clashing with Control Center privates) ----
@Composable
private fun UiStat(value: String, label: String, color: Color = Color.White) {
    Column {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = UiMuted, fontSize = 11.sp)
    }
}

@Composable
private fun UiPanelBox(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UiPanel)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp),
        content = content
    )
}

@Composable
private fun UiSection(title: String) {
    Text(title, color = UiMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun UiKV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = UiMuted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(v, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(UiPanel2)
            .border(1.dp, UiMuted.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = UiAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
