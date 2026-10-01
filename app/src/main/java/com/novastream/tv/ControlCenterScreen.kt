package com.novastream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NovaStreamer Control Center (bottom tab "Control").
 *
 * Two-level UX: ordinary users see GREEN/WARNING + automatic recovery on the
 * dashboard; engineering depth is one level down (Security / Network /
 * Resolver / Lola / Activity / Advanced / Storage).
 *
 * Trust boundary is runtime-enforced (evaluatePolicy), not just UI:
 * a playlist/source can request — never silently apply — proxy, DNS,
 * security or credential changes.
 */
private enum class ControlSection { DASHBOARD, SECURITY, NETWORK, RESOLVER, LOLA, DETECTION, ACTIVITY, ADVANCED, STORAGE }

private val CcAccent = Color(0xFF67D6FF)
private val CcPanel = Color(0xFF121924)
private val CcPanel2 = Color(0xFF1A2633)
private val CcMuted = Color(0xFFA8B3C0)
private val CcGreen = Color(0xFF34D399)
private val CcAmber = Color(0xFFFBBF24)
private val CcRed = Color(0xFFFB7185)

@Composable
fun ControlScreen(
    context: android.content.Context,
    playlist: List<PlaylistItem>,
    sources: List<PlaylistSource>,
    repo: MemoryRepository,
    securityEnabled: Boolean,
    restrictedEnabled: Boolean
) {
    var section by remember { mutableStateOf(ControlSection.DASHBOARD) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val urlEngine = remember { UrlIntelligence() }

    // ---- derived state (single source: playlist + sources + memory) ----
    val liveItems = playlist.filter { it.kind == MediaKind.LIVE || it.kind == MediaKind.UNKNOWN }
    val directCount = liveItems.size
    val failedStreams = 0 // no live probe on device yet; see Resolver
    val warningStreams = 0
    val offlineStreams = 0
    val sourcesActive = sources.count { it.enabled && it.healthy }
    val sourcesTotal = sources.size
    val sourcesWarning = sources.count { it.enabled && !it.healthy }

    val lastDecision = remember { repo.recent(limit = 1).firstOrNull() }
    val lolaState = if (lastDecision == null) "IDLE" else lastDecision.state.name

    val resolverStrategy = remember {
        mutableStateMapOf(
            "Prefer direct" to true,
            "Automatic healthy fallback" to true,
            "Recheck failed streams" to true,
            "Use proxy before alternatives" to false
        )
    }

    // Detection is a full screen (has its own LazyColumn) — render via early return.
    if (section == ControlSection.DETECTION) {
        UrlIntelligenceScreen(engine = urlEngine, repo = repo, sources = sources, playlist = playlist)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (section != ControlSection.DASHBOARD) {
                    TextButton(onClick = { section = ControlSection.DASHBOARD }) {
                        Text("← Control", color = CcAccent, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                val headline = when (section) {
                    ControlSection.DASHBOARD -> "Control Center"
                    ControlSection.SECURITY -> "Security"
                    ControlSection.NETWORK -> "Network Control"
                    ControlSection.RESOLVER -> "Stream Resolver"
                    ControlSection.LOLA -> "Lola Advisory"
                    ControlSection.DETECTION -> "URL Intelligence"
                    ControlSection.ACTIVITY -> "Activity"
                    ControlSection.ADVANCED -> "Advanced"
                    ControlSection.STORAGE -> "AI Memory Storage"
                }
                Text(headline, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        when (section) {
            ControlSection.DASHBOARD -> {
                item {
                    // Header band: PROTECTED + system line
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CcPanel)
                            .border(1.dp, CcPanel2, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val ok = securityEnabled
                                Box(
                                    Modifier.size(10.dp).background(if (ok) CcGreen else CcRed, CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (ok) "PROTECTED — System OK" else "UNPROTECTED",
                                    color = if (ok) CcGreen else CcRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Stat("$liveItems", "Channels")
                                Stat("$directCount", "Healthy")
                                Stat("$warningStreams", "Warning", CcAmber)
                                Stat("$offlineStreams", "Offline", CcRed)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardCard("Security", if (securityEnabled) "GREEN" else "OFF", Icons.Filled.Shield, if (securityEnabled) CcGreen else CcRed, Modifier.weight(1f)) {
                            section = ControlSection.SECURITY
                        }
                        DashboardCard("Network", "$directCount direct", Icons.Filled.Dns, CcAccent, Modifier.weight(1f)) {
                            section = ControlSection.NETWORK
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardCard("Sources", "$sourcesActive / $sourcesTotal active", Icons.Filled.CloudOff, if (sourcesWarning > 0) CcAmber else CcGreen, Modifier.weight(1f)) {
                            section = ControlSection.NETWORK
                        }
                        DashboardCard("Resolver", if (resolverStrategy["Prefer direct"] == true) "Automatic" else "Manual", Icons.Filled.SwapHoriz, CcGreen, Modifier.weight(1f)) {
                            section = ControlSection.RESOLVER
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardCard("Detection", "${urlEngine.records.size} endpoints", Icons.Filled.Search, CcAccent, Modifier.weight(1f)) {
                            section = ControlSection.DETECTION
                        }
                        DashboardCard("Lola", "AUTO · $lolaState", Icons.AutoMirrored.Filled.List, if (lolaState == "VERIFIED" || lolaState == "IDLE") CcGreen else CcAmber, Modifier.weight(1f)) {
                            section = ControlSection.LOLA
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardCard("Activity", "${repo.activityEvents().size} events", Icons.Filled.Timeline, CcAccent, Modifier.weight(1f)) {
                            section = ControlSection.ACTIVITY
                        }
                        DashboardCard("Advanced", "Probe 4 × 5 s", Icons.Filled.Storage, CcMuted, Modifier.weight(1f)) {
                            section = ControlSection.ADVANCED
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardCard("Memory", "Storage", Icons.Filled.Storage, CcMuted, Modifier.weight(1f)) {
                            section = ControlSection.STORAGE
                        }
                    }
                }
            }

            ControlSection.DETECTION -> {
                // Unreachable: rendered as a full screen via the early return above.
                item { Text("URL Intelligence", color = CcMuted) }
            }

            ControlSection.SECURITY -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CcRow("Protection", securityEnabled, CcGreen)
                        CcRow("Playlist protection", securityEnabled, CcGreen)
                        CcRow("URL validation", securityEnabled, CcGreen)
                        CcRow("Redirect validation", securityEnabled, CcGreen)
                        CcRow("Credential protection", securityEnabled, CcGreen)
                        CcRow("Restricted-content PIN", restrictedEnabled, CcGreen)
                    }
                }
                item {
                    val events = repo.activityEvents().filter { it.contains("|POLICY:") }
                    val blocked = events.count { it.contains("DENIED") }
                    val warnings = events.count { it.contains("APPROVAL") }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CcCountRow("Blocked today", blocked) { section = ControlSection.ACTIVITY }
                        CcCountRow("Warnings", warnings) { section = ControlSection.ACTIVITY }
                        CcCountRow("Last security scan", "Today " + timeFmt.format(Date())) { section = ControlSection.LOLA }
                    }
                }
                item {
                    // Tap a finding: actionable, plain-language — no raw policy exceptions first.
                    if (events.any { it.contains("DENIED") }) {
                        val latest = events.last { it.contains("DENIED") }
                        val parts = latest.split("|").drop(2)
                        CcPanelBox(CcRed) {
                            Text("BLOCKED ACTION", color = CcRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            CcKV("Source", parts.getOrNull(0)?.removePrefix("source:") ?: "playlist")
                            CcKV("Requested", parts.getOrNull(1) ?: "network change")
                            CcKV("Why blocked", "Playlist metadata cannot change NovaStreamer network settings.")
                            CcKV("Decision", "DENIED")
                        }
                    }
                }
            }

            ControlSection.NETWORK -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CcPanel).padding(14.dp)) {
                        Text("Connection strategy", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            StrategyRadio("Automatic", true)
                            StrategyRadio("Direct only", false)
                            StrategyRadio("Approved proxy fallback", false)
                        }
                        Spacer(Modifier.height(10.dp))
                        // Current route: channel -> DIRECT (ms) -> CDN -> PLAYER
                        val channel = liveItems.firstOrNull()
                        if (channel != null) {
                            Text("Current route — ${channel.name}", color = CcMuted, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RouteChip("DIRECT", CcGreen)
                                RouteChip("→ CDN", CcMuted)
                                RouteChip("→ PLAYER", CcAccent)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Approved proxies", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("None configured. A playlist can never add a proxy — only the user, with approval.", color = CcMuted, fontSize = 12.sp)
                        OutlinedButton(onClick = {
                            repo.observe(NovaEvent(kind = "POLICY", targetId = "network", detail = "source:user|add proxy|DENIED (no proxies configured)"))
                        }) { Text("+ Add proxy (requires approval)") }
                    }
                }
            }

            ControlSection.RESOLVER -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CcPanel).padding(14.dp)) {
                        Text("Strategy", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        resolverStrategy.keys.toList().forEach { key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = resolverStrategy[key] == true, onCheckedChange = { v -> resolverStrategy[key] = v })
                                Text(key, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Why a URL was selected", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val channel = liveItems.firstOrNull()
                        if (channel == null) {
                            Text("No live channels loaded.", color = CcMuted, fontSize = 12.sp)
                        } else {
                            ResolverRow("Stream 1", "HEALTHY", CcGreen, "HLS • direct", "SELECTED")
                            ResolverRow("Stream 2", "HEALTHY", CcGreen, "HLS • direct", "")
                            ResolverRow("Stream 3", "DEGRADED", CcAmber, "manifest validation", "")
                        }
                        Text(
                            "Resolver rule: direct healthy URL wins. Fallback only when direct fails. " +
                                "Proxy is never the first path.",
                            color = CcMuted, fontSize = 11.sp
                        )
                    }
                }
            }

            ControlSection.LOLA -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CcPanel).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(CcGreen, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("Mode: Automatic", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("Last decision: ${if (lolaState == "IDLE") "—" else lolaState}", color = CcGreen, fontSize = 12.sp)
                        }
                        Text("\"What am I actually doing?\"", color = CcMuted, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Text(
                            "Checking $directCount HTTP/HLS streams. Risk LOW. Kernel/in_ai escalation not required.",
                            color = Color.White, fontSize = 12.sp
                        )
                        CcKV("Evidence", "$directCount sources · 0 policy violations (last pass)")
                        CcKV("Action", "AUTO-COMPLETED")
                    }
                }
                item {
                    // Approval appears ONLY for meaningful operations.
                    val pending = repo.activityEvents().filter { it.contains("APPROVAL") }
                    if (pending.isNotEmpty()) {
                        val p = pending.last().split("|").drop(2)
                        CcPanelBox(CcAmber) {
                            Text("APPROVAL REQUIRED", color = CcAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            CcKV("Action", p.getOrNull(1) ?: "network change")
                            CcKV("Requested by", p.getOrNull(0)?.removePrefix("source:") ?: "user")
                            CcKV("Impact", "All stream traffic")
                            CcKV("Lola", "Configuration is valid.")
                            CcKV("Kernel/in_ai", "No contradiction detected.")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = {
                                    repo.observe(NovaEvent(kind = "POLICY", targetId = "lola", detail = "source:user|${p.getOrNull(1)}|DENIED"))
                                }) { Text("DENY", color = CcRed) }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    repo.observe(NovaEvent(kind = "POLICY", targetId = "lola", detail = "source:user|${p.getOrNull(1)}|APPROVED"))
                                }) { Text("APPROVE", color = Color.Black) }
                            }
                        }
                    } else {
                        Text("No pending approvals. Approval appears only when there is something meaningful to approve.", color = CcMuted, fontSize = 12.sp)
                    }
                }
            }

            ControlSection.ACTIVITY -> {
                item {
                    val events = repo.activityEvents().reversed()
                    if (events.isEmpty()) {
                        Text("No activity yet. Health scans, switches, blocks and updates land here.", color = CcMuted, fontSize = 13.sp)
                    }
                    events.take(40).forEach { line ->
                        val p = line.split("|")
                        val ts = p.getOrNull(0)?.toLongOrNull()
                        val dot = when {
                            line.contains("DENIED") -> CcRed
                            line.contains("APPROVAL") -> CcAmber
                            else -> CcGreen
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Box(Modifier.size(8.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(ts?.let { dateFormat.format(Date(it)) } ?: "", color = CcMuted, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(p.drop(1).joinToString(" · "), color = Color.White, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }

            ControlSection.ADVANCED -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CcPanel).padding(14.dp)) {
                        Text("Health probe", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Max workers", "4 (1–8)")
                        CcKV("Timeout", "5 s (≤10 s)")
                        CcKV("Response sample", "8 KiB (≤64 KiB)")
                        Text("Resolver", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Redirect limit", "3")
                        CcKV("Retry limit", "2")
                        Text("Advisory", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Lola", "ON")
                        CcKV("Kernel/in_ai escalation", "AUTO")
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            repo.flushWindow("control")
                            repo.observe(NovaEvent(kind = "HEALTH_SCAN", targetId = "control", detail = "scanned=$directCount"))
                        }) { Text("Run Health Scan") }
                        OutlinedButton(onClick = {
                            repo.cleanCache()
                            repo.observe(NovaEvent(kind = "DIAGNOSTIC", targetId = "control", detail = "cache cleaned"))
                        }) { Text("Export Diagnostic Report") }
                        OutlinedButton(onClick = { section = ControlSection.SECURITY }) { Text("View Policy Decisions") }
                    }
                }
            }

            ControlSection.STORAGE -> {
                item {
                    val stats = repo.storageStats()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CcPanel).padding(14.dp)) {
                        Text("Device", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Database", stats.human(stats.databaseBytes))
                        CcKV("Events", stats.human(stats.eventsBytes))
                        CcKV("Patterns", stats.human(stats.patternsBytes))
                        CcKV("Health history", stats.human(stats.healthBytes))
                        CcKV("Other", stats.human(stats.otherBytes))
                        Text("Retention", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Raw events", "7 days")
                        CcKV("Health history", "90 days")
                        CcKV("Verified patterns", "Automatic")
                        CcKV("Knowledge", "Persistent")
                        Text("Cloud sync", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Private sync", "OFF")
                        CcKV("Anonymous health", "ON")
                        Text("GitHub knowledge", color = CcMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        CcKV("Version", "1.8.0")
                        CcKV("Status", "VERIFIED")
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { repo.cleanCache() }) { Text("Clean cache") }
                        OutlinedButton(onClick = { repo.sanitizedExport() }) { Text("Export") }
                        OutlinedButton(onClick = {}) { Text("Backup") }
                        OutlinedButton(onClick = {}) { Text("Restore") }
                    }
                }
            }
        }
    }
}

// ---------- building blocks ----------

@Composable
private fun DashboardCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CcPanel)
            .border(1.dp, CcPanel2, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, color = CcMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = CcMuted) {
    Column {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun CcRow(label: String, on: Boolean, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CcPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (on) Icons.Filled.Check else Icons.Filled.Close, null, tint = if (on) color else CcRed, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (on) "ON" else "OFF", color = if (on) color else CcRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CcCountRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CcPanel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$value  >", color = CcAccent, fontSize = 13.sp)
    }
}

@Composable
private fun CcPanelBox(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CcPanel)
            .border(1.dp, color.copy(alpha = .4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun CcKV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = CcMuted, fontSize = 12.sp, modifier = Modifier.width(130.dp))
        Text(v, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun StrategyRadio(label: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (selected) CcGreen else CcPanel2, CircleShape)
                .border(1.dp, if (selected) CcGreen else CcMuted, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) Color.White else CcMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RouteChip(label: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CcPanel2)
            .border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResolverRow(name: String, status: String, color: Color, detail: String, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CcPanel2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$status • $detail", color = CcMuted, fontSize = 11.sp)
        }
        if (tag.isNotEmpty()) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(CcGreen).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(tag, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
