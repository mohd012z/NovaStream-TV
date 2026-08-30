package com.novastream.tv

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaStreamApp(this) }
    }
}

enum class OrientationChoice { PORTRAIT, LANDSCAPE, AUTO }
enum class AppPage { HOME, LIVE, MOVIES, SERIES, SEARCH, HISTORY, DOWNLOADS, SETTINGS }

@Composable
fun NovaStreamApp(activity: MainActivity) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository(context) }
    var selectedOrientation by remember { mutableStateOf<OrientationChoice?>(null) }
    var page by remember { mutableStateOf(AppPage.HOME) }
    var playing by remember { mutableStateOf<PlaylistItem?>(null) }
    var libraryVersion by remember { mutableIntStateOf(0) }
    val playlist = remember(libraryVersion) { repo.playlist() }
    val epg = remember(libraryVersion) { repo.epg() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF6FD6FF), secondary = Color(0xFF8BE6C5),
        background = Color(0xFF101114), surface = Color(0xFF181A20)
    )) {
        val request = playing
        if (request != null) {
            PlayerScreen(request, onBack = { playing = null })
        } else {
            Scaffold(bottomBar = { BottomBar(page) { page = it } }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                    when (page) {
                        AppPage.HOME -> HomeScreen(activity, playlist, epg, { page = it }, { playing = it })
                        AppPage.LIVE -> MediaLibraryScreen("Live TV", playlist.filter { it.kind == MediaKind.LIVE || it.kind == MediaKind.UNKNOWN }, epg) { playing = it }
                        AppPage.MOVIES -> MediaLibraryScreen("Movies", playlist.filter { it.kind == MediaKind.MOVIE }, epg) { playing = it }
                        AppPage.SERIES -> MediaLibraryScreen("Series", playlist.filter { it.kind == MediaKind.SERIES }, epg) { playing = it }
                        AppPage.SEARCH -> SearchScreen(playlist, epg) { playing = it }
                        AppPage.HISTORY -> HistoryScreen(activity)
                        AppPage.DOWNLOADS -> DownloadsScreen()
                        AppPage.SETTINGS -> SettingsScreen(repo, playlist, epg) { libraryVersion++ }
                    }
                    if (selectedOrientation == null) {
                        OrientationDialog { choice ->
                            selectedOrientation = choice
                            activity.requestedOrientation = when (choice) {
                                OrientationChoice.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                OrientationChoice.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                OrientationChoice.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationBar {
        listOf(AppPage.HOME to "Home", AppPage.LIVE to "Live", AppPage.SEARCH to "Search", AppPage.HISTORY to "History", AppPage.DOWNLOADS to "Downloads")
            .forEach { (page, label) ->
                NavigationBarItem(selected = current == page, onClick = { onSelect(page) }, icon = { Text(label.take(1)) }, label = { Text(label) })
            }
    }
}

@Composable
private fun OrientationDialog(onSelect: (OrientationChoice) -> Unit) {
    AlertDialog(onDismissRequest = {}, title = { Text("Screen orientation", fontWeight = FontWeight.Bold) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose how PerfectTV Enhanced opens. You can rotate again inside the player.")
            OrientationButton("▯  Portrait", OrientationChoice.PORTRAIT, onSelect)
            OrientationButton("▭  Landscape", OrientationChoice.LANDSCAPE, onSelect)
            OrientationButton("↻  Auto rotate", OrientationChoice.AUTO, onSelect)
        }
    }, confirmButton = {})
}

@Composable
private fun OrientationButton(label: String, value: OrientationChoice, onSelect: (OrientationChoice) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { onSelect(value) }, shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp) {
        Text(label, Modifier.padding(18.dp), fontSize = 17.sp)
    }
}

@Composable
private fun HomeScreen(
    activity: MainActivity,
    playlist: List<PlaylistItem>,
    epg: List<EpgProgramme>,
    navigate: (AppPage) -> Unit,
    play: (PlaylistItem) -> Unit
) {
    val recent = remember { PlaybackStore(activity).recent(3) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("N", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column { Text("PerfectTV Enhanced", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Authorized streams, enhanced player.", fontSize = 12.sp, color = Color.LightGray) }
        }
        Spacer(Modifier.height(18.dp))
        Text("${playlist.size} items • ${epg.size} EPG programmes", color = Color.LightGray, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard("LIVE", "TV channels", Modifier.weight(1f)) { navigate(AppPage.LIVE) }
            FeatureCard("MOVIES", "On demand", Modifier.weight(1f)) { navigate(AppPage.MOVIES) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard("SERIES", "Episodes", Modifier.weight(1f)) { navigate(AppPage.SERIES) }
            FeatureCard("SEARCH", "Find everything", Modifier.weight(1f)) { navigate(AppPage.SEARCH) }
        }
        Spacer(Modifier.height(22.dp))
        Text("Continue watching", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (recent.isEmpty()) EmptyCard("No recent playback yet") else recent.forEach { record ->
            val item = playlist.firstOrNull { it.id == record.id }
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(enabled = item != null) { item?.let(play) }, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(record.title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { record.progress }, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(onClick = { navigate(AppPage.HISTORY) }, label = { Text("History") })
            AssistChip(onClick = { navigate(AppPage.DOWNLOADS) }, label = { Text("Downloads") })
            AssistChip(onClick = { navigate(AppPage.SETTINGS) }, label = { Text("Settings") })
        }
    }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(110.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(subtitle, color = Color.LightGray, fontSize = 12.sp) }
    }
}

@Composable
private fun MediaLibraryScreen(title: String, items: List<PlaylistItem>, epg: List<EpgProgramme>, play: (PlaylistItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("${items.size} items", color = Color.LightGray)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (items.isEmpty()) EmptyCard("Import an authorized M3U playlist in Settings") else items.forEach { item ->
                val now = EpgParser.now(epg, item.tvgId)
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { play(item) }, shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        Text(item.groupTitle.orEmpty(), color = Color.LightGray, fontSize = 12.sp)
                        if (now != null) Text("Now: ${now.title}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(items: List<PlaylistItem>, epg: List<EpgProgramme>, play: (PlaylistItem) -> Unit) {
    var q by remember { mutableStateOf("") }
    val matches = remember(q, items) { if (q.isBlank()) emptyList() else items.filter { it.name.contains(q, true) || it.groupTitle.orEmpty().contains(q, true) }.take(100) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Search", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Channels, movies, series…") })
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (q.isBlank()) EmptyCard("Type to search your library") else if (matches.isEmpty()) EmptyCard("No matches") else matches.forEach { item ->
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { play(item) }, shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        EpgParser.now(epg, item.tvgId)?.let { Text("Now: ${it.title}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(activity: MainActivity) {
    val items = remember { PlaybackStore(activity).recent(30) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Watch History", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) EmptyCard("No playback history yet") else items.forEach { Text("• ${it.title} — ${(it.progress * 100).toInt()}%", Modifier.padding(vertical = 5.dp)) }
    }
}

@Composable
private fun DownloadsScreen() {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Queue / Downloading / Paused / Completed / Failed states are prepared for content authorized for offline storage.", color = Color.LightGray)
        Spacer(Modifier.height(14.dp)); EmptyCard("No downloads")
    }
}

@Composable
private fun SettingsScreen(repo: LibraryRepository, playlist: List<PlaylistItem>, epg: List<EpgProgramme>, onLibraryChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PlayerPreferences(context) }
    var status by remember { mutableStateOf("") }
    var brightness by remember { mutableFloatStateOf(prefs.brightnessSensitivity) }
    var volume by remember { mutableFloatStateOf(prefs.volumeSensitivity) }
    var autoRetry by remember { mutableStateOf(prefs.autoRetry) }

    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = "Importing playlist…"
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
            withContext(Dispatchers.IO) { repo.savePlaylist(raw) }
            status = "Playlist imported: ${M3uParser.parse(raw).size} items"
            onLibraryChanged()
        }
    }
    val epgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = "Importing EPG…"
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
            withContext(Dispatchers.IO) { repo.saveEpg(raw) }
            status = "EPG imported: ${EpgParser.parse(raw).size} programmes"
            onLibraryChanged()
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Library", fontWeight = FontWeight.Bold)
        Text("${playlist.size} playlist items • ${epg.size} EPG programmes", color = Color.LightGray)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { m3uLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }) { Text("Import M3U") }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { epgLauncher.launch(arrayOf("application/xml", "text/xml", "*/*")) }) { Text("Import XMLTV EPG") }
        Spacer(Modifier.height(12.dp))
        Text("Remote Sources", fontWeight = FontWeight.Bold)
        Text("Use only playlists and EPG sources you are authorized to access.", color = Color.LightGray, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                status = "Loading PerfectTV Free playlist…"
                val m3u = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(RemoteSourceLoader.PERFECTTV_FREE_M3U) }
                if (!m3u.ok) {
                    status = "Playlist failed: ${m3u.message}"
                } else {
                    withContext(Dispatchers.IO) { repo.savePlaylist(m3u.body) }
                    status = "Playlist loaded: ${M3uParser.parse(m3u.body).size} items"
                    onLibraryChanged()
                }
            }
        }) { Text("Load PerfectTV Free M3U") }
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            scope.launch {
                status = "Loading PerfectTV EPG…"
                val xml = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(RemoteSourceLoader.PERFECTTV_FREE_EPG) }
                if (!xml.ok) {
                    status = "EPG failed: ${xml.message}"
                } else {
                    withContext(Dispatchers.IO) { repo.saveEpg(xml.body) }
                    status = "EPG loaded: ${EpgParser.parse(xml.body).size} programmes"
                    onLibraryChanged()
                }
            }
        }) { Text("Load PerfectTV XMLTV EPG") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { repo.clear(); onLibraryChanged(); status = "Library cleared" }) { Text("Clear library") }
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = MaterialTheme.colorScheme.secondary) }

        Spacer(Modifier.height(22.dp)); Text("Player gestures", fontWeight = FontWeight.Bold)
        Text("Brightness sensitivity ${(brightness * 100).toInt()}%", color = Color.LightGray)
        Slider(value = brightness, onValueChange = { brightness = it; prefs.brightnessSensitivity = it }, valueRange = 0.10f..0.60f)
        Text("Volume sensitivity ${(volume * 100).toInt()}%", color = Color.LightGray)
        Slider(value = volume, onValueChange = { volume = it; prefs.volumeSensitivity = it }, valueRange = 0.20f..1.0f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoRetry, onCheckedChange = { autoRetry = it; prefs.autoRetry = it })
            Spacer(Modifier.width(10.dp)); Text("Auto retry playback")
        }

        Spacer(Modifier.height(20.dp)); Text("v3 player", fontWeight = FontWeight.Bold)
        Text("• HLS / DASH / RTSP via Media3", color = Color.LightGray)
        Text("• Picture-in-Picture button", color = Color.LightGray)
        Text("• Resume / Continue Watching", color = Color.LightGray)
        Text("• Smooth brightness / volume", color = Color.LightGray)
        Text("• Automatic retry on playback error", color = Color.LightGray)
        Text("• Subtitle/audio/quality track button ready in player", color = Color.LightGray)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(Modifier.fillMaxWidth().height(90.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) { Text(text, color = Color.LightGray) }
    }
}
