package com.novastream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val SourceAccent = Color(0xFF67D6FF)
private val SourceAccent2 = Color(0xFF78F1C7)
private val SourcePanel = Color(0xFF1A2633)
private val SourceMuted = Color(0xFFA8B3C0)

private data class M3uPreset(val name: String, val url: String)
private val publicPresets = listOf(
    M3uPreset("PerfectTV Free (Live/Movies)", RemoteSourceLoader.PERFECTTV_FREE_M3U),
    M3uPreset("iptv-org | All channels", "https://iptv-org.github.io/iptv/index.m3u"),
    M3uPreset("iptv-org | Malaysia", "https://iptv-org.github.io/iptv/countries/my.m3u"),
    M3uPreset("iptv-org | Family", "https://iptv-org.github.io/iptv/categories/family.m3u"),
    M3uPreset("iptv-org | Lifestyle", "https://iptv-org.github.io/iptv/categories/lifestyle.m3u"),
    M3uPreset("iptv-org | Movies", "https://iptv-org.github.io/iptv/categories/movies.m3u"),
    M3uPreset("iptv-org | News", "https://iptv-org.github.io/iptv/categories/news.m3u"),
    M3uPreset("iptv-org | Sports", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
    M3uPreset("iptv-org | Outdoor", "https://iptv-org.github.io/iptv/categories/outdoor.m3u")
)

@Composable
fun PlaylistSourcesScreen(repo: LibraryRepository, onLibraryChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlaylistSourceStore(context) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(store.all()) }
    var dialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaylistSource?>(null) }
    var status by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var addingAll by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    fun reload() { sources = store.all() }
    fun clearSelection() { selectedIds.clear() }
    fun toggleSelect(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (selectionMode) {
                    BulkActionBar(
                        selectedCount = selectedIds.size,
                        onEnable = {
                            selectedIds.forEach { store.setEnabled(it, true) }
                            store.rebuildLibrary(repo); reload(); onLibraryChanged()
                            status = "Enabled ${selectedIds.size} source(s)"
                            clearSelection()
                        },
                        onDisable = {
                            selectedIds.forEach { store.setEnabled(it, false) }
                            store.rebuildLibrary(repo); reload(); onLibraryChanged()
                            status = "Disabled ${selectedIds.size} source(s)"
                            clearSelection()
                        },
                        onDelete = { confirmDelete = true },
                        onCancel = { clearSelection() }
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Playlists", fontSize = 28.sp, fontWeight = FontWeight.Black)
                            Text("${sources.size} saved sources • ${sources.sumOf { it.itemCount }} indexed items", color = SourceMuted)
                        }
                        if (addingAll) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                        IconButton(enabled = !addingAll, onClick = {
                            scope.launch {
                                addingAll = true
                                val existingUrls = sources.map { it.url }.toSet()
                                val missing = publicPresets.filter { it.url !in existingUrls }
                                for (preset in missing) {
                                    status = "Adding ${preset.name}…"
                                    val result = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(preset.url) }
                                    if (result.ok) {
                                        val count = withContext(Dispatchers.Default) { M3uParser.parse(result.body).size }
                                        if (count > 0) {
                                            store.create(preset.name, preset.url, result.body, count)
                                        }
                                    }
                                }
                                store.rebuildLibrary(repo)
                                reload()
                                onLibraryChanged()
                                status = if (missing.isEmpty()) "All suggested playlists already added" else "Added ${missing.size} suggested playlists"
                                addingAll = false
                            }
                        }) { Icon(Icons.Filled.PlaylistAdd, "Add all suggested playlists", tint = SourceAccent) }
                        IconButton(onClick = {
                            sources.filter { it.url.startsWith("http") }.forEach { source ->
                                scope.launch {
                                    busyId = source.id
                                    val result = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(source.url) }
                                    if (result.ok) {
                                        val count = withContext(Dispatchers.Default) { M3uParser.parse(result.body).size }
                                        store.upsert(source.copy(itemCount = count, updatedAt = System.currentTimeMillis(), healthy = count > 0, message = if (count > 0) "Ready" else "No playable entries"), result.body.takeIf { count > 0 })
                                    } else store.upsert(source.copy(healthy = false, message = result.message, updatedAt = System.currentTimeMillis()))
                                    store.rebuildLibrary(repo); reload(); onLibraryChanged(); busyId = null
                                }
                            }
                        }) { Icon(Icons.Filled.Refresh, "Refresh all", tint = SourceAccent) }
                    }
                }
            }
            if (status.isNotBlank()) item { Text(status, color = SourceAccent2, fontSize = 12.sp) }
            if (sources.isEmpty()) item {
                Surface(shape = RoundedCornerShape(20.dp), color = SourcePanel) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.PlaylistAdd, null, tint = SourceAccent, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No playlists yet", fontWeight = FontWeight.Bold)
                        Text("Tap + to add an authorized M3U/M3U8 source.", color = SourceMuted)
                    }
                }
            }
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    busy = busyId == source.id,
                    selectionMode = selectionMode,
                    selected = selectedIds.contains(source.id),
                    onToggleSelect = { toggleSelect(source.id) },
                    onRefresh = {
                        scope.launch {
                            busyId = source.id; status = "Refreshing ${source.name}…"
                            val result = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(source.url) }
                            if (result.ok) {
                                val count = withContext(Dispatchers.Default) { M3uParser.parse(result.body).size }
                                store.upsert(source.copy(itemCount = count, updatedAt = System.currentTimeMillis(), healthy = count > 0, message = if (count > 0) "Ready" else "No playable entries"), result.body.takeIf { count > 0 })
                                status = if (count > 0) "${source.name}: $count items" else "${source.name}: no playable entries"
                            } else {
                                store.upsert(source.copy(healthy = false, message = result.message, updatedAt = System.currentTimeMillis()))
                                status = "${source.name}: ${result.message}"
                            }
                            store.rebuildLibrary(repo); reload(); onLibraryChanged(); busyId = null
                        }
                    },
                    onEdit = { editing = source; dialog = true },
                    onDelete = {
                        store.delete(source.id); store.rebuildLibrary(repo); reload(); onLibraryChanged()
                        status = "${source.name} removed"
                    }
                )
            }
        }

        if (!selectionMode) {
            FloatingActionButton(
                onClick = { editing = null; dialog = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                containerColor = SourceAccent,
                contentColor = Color(0xFF071017)
            ) { Icon(Icons.Filled.Add, "Add playlist", modifier = Modifier.size(30.dp)) }
        }
    }

    if (dialog) {
        PlaylistUrlDialog(
            initial = editing,
            onDismiss = { dialog = false; editing = null },
            onConfirm = { name, url ->
                val old = editing
                dialog = false; editing = null
                scope.launch {
                    status = "Checking $name…"; busyId = old?.id
                    val result = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(url) }
                    if (!result.ok) {
                        status = "Playlist failed: ${result.message}"
                        if (old != null) store.upsert(old.copy(name = name, url = url, healthy = false, message = result.message, updatedAt = System.currentTimeMillis()))
                    } else {
                        val count = withContext(Dispatchers.Default) { M3uParser.parse(result.body).size }
                        if (count == 0) status = "No playable M3U entries found"
                        else {
                            if (old == null) store.create(name, url, result.body, count)
                            else store.upsert(old.copy(name = name, url = url, itemCount = count, updatedAt = System.currentTimeMillis(), healthy = true, message = "Ready"), result.body)
                            store.rebuildLibrary(repo); onLibraryChanged()
                            status = "$name loaded: $count items"
                        }
                    }
                    reload(); busyId = null
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selectedIds.size} playlist(s)?", fontWeight = FontWeight.Bold) },
            text = { Text("This removes the selected playlist sources and their indexed items. This cannot be undone.", color = SourceMuted) },
            confirmButton = {
                Button(onClick = {
                    val ids = selectedIds.toList()
                    ids.forEach { store.delete(it) }
                    store.rebuildLibrary(repo); reload(); onLibraryChanged()
                    status = "Deleted ${ids.size} source(s)"
                    confirmDelete = false
                    clearSelection()
                }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BulkActionBar(
    selectedCount: Int,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = SourcePanel) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$selectedCount selected", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel selection", tint = SourceMuted) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onEnable, label = { Text("Enable selected") }, leadingIcon = { Icon(Icons.Filled.Visibility, null) })
                AssistChip(onClick = onDisable, label = { Text("Disable selected") }, leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) })
                AssistChip(onClick = onDelete, label = { Text("Delete selected") }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: PlaylistSource,
    busy: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = SourcePanel) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Icon(
                    if (source.healthy) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    null,
                    tint = if (!source.enabled) SourceMuted else if (source.healthy) SourceAccent2 else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable(enabled = true) { onToggleSelect() }) {
                    Text(source.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (source.enabled) Color.Unspecified else SourceMuted)
                    Text(source.url, color = SourceMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "${source.itemCount} channels • ${if (source.enabled) source.message else "Disabled"}",
                color = if (!source.enabled) SourceMuted else if (source.healthy) SourceAccent2 else SourceMuted,
                fontSize = 12.sp
            )
            if (source.updatedAt > 0) Text("Updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.updatedAt))}", color = SourceMuted, fontSize = 11.sp)
            if (!selectionMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable
private fun PlaylistUrlDialog(initial: PlaylistSource?, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var selected by remember(initial) { mutableStateOf(initial?.url) }
    val valid = url.trim().let { it.startsWith("https://") || it.startsWith("http://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Enter playlist URL" else "Edit playlist", color = SourceAccent, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Playlist name", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = { Text("Enter playlist name…") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("Playlist URL", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(url, { url = it; selected = null }, Modifier.fillMaxWidth(), placeholder = { Text("https://example.com/playlist.m3u") }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                Text("Suggestions", fontWeight = FontWeight.SemiBold)
                publicPresets.forEach { preset ->
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = preset.url; url = preset.url; name = preset.name },
                        shape = RoundedCornerShape(14.dp), color = SourcePanel
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.name, color = SourceAccent2, fontWeight = FontWeight.SemiBold)
                                Text(preset.url, color = SourceMuted, fontSize = 10.sp)
                            }
                            RadioButton(selected = selected == preset.url, onClick = { selected = preset.url; url = preset.url; name = preset.name })
                        }
                    }
                }
                Text("Use only playlists and streams you are authorized to access.", color = SourceMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(name.trim().ifBlank { "Custom playlist" }, url.trim()) }) { Text("Done") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
