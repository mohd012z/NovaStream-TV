package com.novastream.tv

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Accent = Color(0xFF67D6FF)
private val Accent2 = Color(0xFF78F1C7)
private val Bg = Color(0xFF080B10)
private val Panel = Color(0xFF121924)
private val Panel2 = Color(0xFF1A2633)
private val Muted = Color(0xFFA8B3C0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PerfectTvEnhancedApp(this) }
    }
}

enum class OrientationChoice { PORTRAIT, LANDSCAPE, AUTO }
enum class AppPage { HOME, LIVE, MOVIES, SERIES, SEARCH, HISTORY, DOWNLOADS, SETTINGS }

@Composable
fun PerfectTvEnhancedApp(activity: MainActivity) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository(context) }
    var showSplash by remember { mutableStateOf(true) }
    var selectedOrientation by remember { mutableStateOf<OrientationChoice?>(null) }
    var page by remember { mutableStateOf(AppPage.HOME) }
    var playing by remember { mutableStateOf<PlaylistItem?>(null) }
    var libraryVersion by remember { mutableIntStateOf(0) }
    var playlist by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var epg by remember { mutableStateOf<List<EpgProgramme>>(emptyList()) }
    var loadingLibrary by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(850)
        showSplash = false
    }

    LaunchedEffect(libraryVersion) {
        loadingLibrary = true
        val loaded = withContext(Dispatchers.IO) { repo.playlist() to repo.epg() }
        playlist = loaded.first
        epg = loaded.second
        loadingLibrary = false
    }

    val epgIndex = remember(epg) { EpgIndex(epg) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent2,
            background = Bg,
            surface = Panel,
            surfaceVariant = Panel2
        )
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            val request = playing
            if (request != null) {
                PlayerScreen(request, onBack = { playing = null })
            } else {
                Scaffold(
                    containerColor = Bg,
                    bottomBar = { BottomBar(page) { page = it } }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            AppPage.HOME -> HomeScreen(activity, playlist, epgIndex, epg.size, loadingLibrary, { page = it }, { playing = it })
                            AppPage.LIVE -> MediaLibraryScreen("Live TV", playlist.filter { it.kind == MediaKind.LIVE || it.kind == MediaKind.UNKNOWN }, epgIndex) { playing = it }
                            AppPage.MOVIES -> MediaLibraryScreen("Movies", playlist.filter { it.kind == MediaKind.MOVIE }, epgIndex) { playing = it }
                            AppPage.SERIES -> MediaLibraryScreen("Series", playlist.filter { it.kind == MediaKind.SERIES }, epgIndex) { playing = it }
                            AppPage.SEARCH -> SearchScreen(playlist, epgIndex) { playing = it }
                            AppPage.HISTORY -> HistoryScreen(activity, playlist) { playing = it }
                            AppPage.DOWNLOADS -> DownloadsScreen()
                            AppPage.SETTINGS -> SettingsScreen(repo, playlist, epg) { libraryVersion++ }
                        }
                    }
                }
            }

            AnimatedVisibility(showSplash, enter = fadeIn(), exit = fadeOut()) {
                BrandSplash()
            }

            if (!showSplash && selectedOrientation == null && playing == null) {
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

@Composable
private fun BrandSplash() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF153449), Bg), radius = 900f)
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(92.dp).shadow(24.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF4B69FF))))
                    .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(58.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("PerfectTV Enhanced", fontWeight = FontWeight.Black, fontSize = 28.sp, color = Color.White)
            Text("Fast • Visual • Smart Player", color = Accent2, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BottomBar(current: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0D1118), tonalElevation = 10.dp) {
        listOf(
            Triple(AppPage.HOME, "Home", Icons.Filled.Home),
            Triple(AppPage.LIVE, "Live", Icons.Filled.LiveTv),
            Triple(AppPage.SEARCH, "Search", Icons.Filled.Search),
            Triple(AppPage.HISTORY, "History", Icons.Filled.History),
            Triple(AppPage.DOWNLOADS, "Downloads", Icons.Filled.Download)
        ).forEach { (page, label, icon) ->
            NavigationBarItem(
                selected = current == page,
                onClick = { onSelect(page) },
                icon = { Icon(icon, label) },
                label = { Text(label, maxLines = 1, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Accent.copy(alpha = .22f))
            )
        }
    }
}

@Composable
private fun OrientationDialog(onSelect: (OrientationChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Choose screen mode", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You can rotate again anytime inside the player.", color = Muted)
                OrientationButton(Icons.Filled.StayCurrentPortrait, "Portrait", OrientationChoice.PORTRAIT, onSelect)
                OrientationButton(Icons.Filled.StayCurrentLandscape, "Landscape", OrientationChoice.LANDSCAPE, onSelect)
                OrientationButton(Icons.Filled.ScreenRotation, "Auto rotate", OrientationChoice.AUTO, onSelect)
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun OrientationButton(icon: ImageVector, label: String, value: OrientationChoice, onSelect: (OrientationChoice) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable { onSelect(value) },
        shape = RoundedCornerShape(16.dp),
        color = Panel2
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeScreen(
    activity: MainActivity,
    playlist: List<PlaylistItem>,
    epgIndex: EpgIndex,
    epgCount: Int,
    loading: Boolean,
    navigate: (AppPage) -> Unit,
    play: (PlaylistItem) -> Unit
) {
    val recent = remember(playlist) { PlaybackStore(activity).recent(8) }
    val live = remember(playlist) { playlist.filter { it.kind == MediaKind.LIVE || it.kind == MediaKind.UNKNOWN }.take(10) }
    val movies = remember(playlist) { playlist.filter { it.kind == MediaKind.MOVIE }.take(10) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(54.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PerfectTV Enhanced", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Premium visual player", color = Accent2, fontSize = 12.sp)
                }
                IconButton(onClick = { navigate(AppPage.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings", tint = Muted) }
            }
        }

        item {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Text("${playlist.size} items • $epgCount EPG programmes", color = Muted, fontSize = 12.sp)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard("LIVE TV", "Watch channels", Icons.Filled.LiveTv, Modifier.weight(1f)) { navigate(AppPage.LIVE) }
                FeatureCard("MOVIES", "On demand", Icons.Filled.Movie, Modifier.weight(1f)) { navigate(AppPage.MOVIES) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard("SERIES", "Episodes", Icons.Filled.VideoLibrary, Modifier.weight(1f)) { navigate(AppPage.SERIES) }
                FeatureCard("SEARCH", "Find anything", Icons.Filled.Search, Modifier.weight(1f)) { navigate(AppPage.SEARCH) }
            }
        }

        if (live.isNotEmpty()) {
            item { SectionHeader("Live now", "See all") { navigate(AppPage.LIVE) } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(live, key = { it.id }) { item ->
                        PosterChannelCard(item, epgIndex.now(item.tvgId)) { play(item) }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Continue watching", "History") { navigate(AppPage.HISTORY) } }
            items(recent, key = { it.id }) { record ->
                val item = playlist.firstOrNull { it.id == record.id }
                GlassRow(onClick = { item?.let(play) }) {
                    Icon(Icons.Filled.PlayCircle, null, tint = Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { record.progress }, Modifier.fillMaxWidth(), color = Accent)
                    }
                }
            }
        }

        if (movies.isNotEmpty()) {
            item { SectionHeader("Movies", "See all") { navigate(AppPage.MOVIES) } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(movies, key = { it.id }) { item -> PosterChannelCard(item, null) { play(item) } }
                }
            }
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Accent, Color(0xFF536BFF))))
            .border(1.dp, Color.White.copy(alpha = .30f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.fillMaxSize(.68f)) }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(118.dp).shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Panel2, Color(0xFF132638))))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accent)
            }
            Column {
                Text(title, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = Accent) }
    }
}

@Composable
private fun PosterChannelCard(item: PlaylistItem, now: EpgProgramme?, onClick: () -> Unit) {
    Surface(
        Modifier.width(150.dp).height(176.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = Panel2
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(100.dp).background(Color(0xFF0E1620)), contentAlignment = Alignment.Center) {
                if (!item.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.logoUrl,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(item.name.take(3).uppercase(), fontWeight = FontWeight.Black, color = Accent, fontSize = 22.sp)
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: item.groupTitle.orEmpty(), color = if (now != null) Accent2 else Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MediaLibraryScreen(title: String, items: List<PlaylistItem>, epgIndex: EpgIndex, play: (PlaylistItem) -> Unit) {
    var group by remember { mutableStateOf("All") }
    val groups = remember(items) { listOf("All") + items.mapNotNull { it.groupTitle?.takeIf(String::isNotBlank) }.distinct().take(20) }
    val filtered = remember(items, group) { if (group == "All") items else items.filter { it.groupTitle == group } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("${filtered.size} items", color = Muted)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g, maxLines = 1) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyCard("Import an authorized M3U playlist in Settings") }
        } else {
            items(filtered, key = { it.id }) { item ->
                val now = epgIndex.now(item.tvgId)
                ChannelRow(item, now, if (now != null) epgIndex.progress(now) else 0f) { play(item) }
            }
        }
    }
}

@Composable
private fun ChannelRow(item: PlaylistItem, now: EpgProgramme?, progress: Float, onClick: () -> Unit) {
    GlassRow(onClick = onClick) {
        Box(
            Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A111A)),
            contentAlignment = Alignment.Center
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(model = item.logoUrl, contentDescription = item.name, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            } else {
                Text(item.name.take(3).uppercase(), color = Accent, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.PlayArrow, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            Text(item.groupTitle.orEmpty().ifBlank { "TV" }, color = Muted, fontSize = 11.sp, maxLines = 1)
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                Text("Now: ${now.title}", color = Accent2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp), color = Accent2, trackColor = Color.White.copy(alpha = .08f))
            }
        }
    }
}

@Composable
private fun GlassRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Panel2, Panel)))
            .border(1.dp, Color.White.copy(alpha = .07f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun SearchScreen(items: List<PlaylistItem>, epgIndex: EpgIndex, play: (PlaylistItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(items, query) {
        if (query.isBlank()) emptyList() else items.filter {
            it.name.contains(query, true) || it.groupTitle.orEmpty().contains(query, true)
        }.take(150)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Search", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Channel, movie or series") },
                singleLine = true
            )
        }
        items(results, key = { it.id }) { item ->
            val now = epgIndex.now(item.tvgId)
            ChannelRow(item, now, if (now != null) epgIndex.progress(now) else 0f) { play(item) }
        }
    }
}

@Composable
private fun HistoryScreen(activity: MainActivity, playlist: List<PlaylistItem>, play: (PlaylistItem) -> Unit) {
    val recent = remember(playlist) { PlaybackStore(activity).recent(50) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("History", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        if (recent.isEmpty()) item { EmptyCard("No playback history yet") }
        items(recent, key = { it.id }) { record ->
            val item = playlist.firstOrNull { it.id == record.id }
            GlassRow(onClick = { item?.let(play) }) {
                Icon(Icons.Filled.History, null, tint = Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(record.title, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { record.progress }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen() {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Downloads", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))
        EmptyCard("Offline downloads are reserved for authorized VOD sources.")
    }
}

@Composable
private fun SettingsScreen(
    repo: LibraryRepository,
    playlist: List<PlaylistItem>,
    epg: List<EpgProgramme>,
    onLibraryChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PlayerPreferences(context) }
    var status by remember { mutableStateOf("") }
    var brightness by remember { mutableFloatStateOf(prefs.brightnessSensitivity) }
    var volume by remember { mutableFloatStateOf(prefs.volumeSensitivity) }
    var autoRetry by remember { mutableStateOf(prefs.autoRetry) }

    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "Importing playlist…"
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
            withContext(Dispatchers.IO) { repo.savePlaylist(raw) }
            status = "Playlist imported: ${withContext(Dispatchers.Default) { M3uParser.parse(raw).size }} items"
            onLibraryChanged()
        }
    }
    val epgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "Importing EPG…"
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
            withContext(Dispatchers.IO) { repo.saveEpg(raw) }
            status = "EPG imported in background"
            onLibraryChanged()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item { Text("${playlist.size} playlist items • ${epg.size} EPG programmes", color = Muted) }
        item {
            GlassRow(onClick = { m3uLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }) {
                Icon(Icons.Filled.PlaylistAdd, null, tint = Accent); Spacer(Modifier.width(12.dp)); Text("Import local M3U", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            GlassRow(onClick = { epgLauncher.launch(arrayOf("application/xml", "text/xml", "*/*")) }) {
                Icon(Icons.Filled.CalendarMonth, null, tint = Accent2); Spacer(Modifier.width(12.dp)); Text("Import XMLTV EPG", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Button(onClick = {
                scope.launch {
                    status = "Loading PerfectTV Free playlist…"
                    val m3u = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(RemoteSourceLoader.PERFECTTV_FREE_M3U) }
                    if (!m3u.ok) status = "Playlist failed: ${m3u.message}" else {
                        withContext(Dispatchers.IO) { repo.savePlaylist(m3u.body) }
                        status = "Playlist saved. Refreshing library…"
                        onLibraryChanged()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("Load PerfectTV Free M3U") }
        }
        item {
            OutlinedButton(onClick = {
                scope.launch {
                    status = "Loading PerfectTV EPG…"
                    val xml = withContext(Dispatchers.IO) { RemoteSourceLoader.fetch(RemoteSourceLoader.PERFECTTV_FREE_EPG) }
                    if (!xml.ok) status = "EPG failed: ${xml.message}" else {
                        withContext(Dispatchers.IO) { repo.saveEpg(xml.body) }
                        status = "EPG saved. Refreshing library…"
                        onLibraryChanged()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.CloudSync, null); Spacer(Modifier.width(8.dp)); Text("Load PerfectTV XMLTV EPG") }
        }
        if (status.isNotBlank()) item { Text(status, color = Accent2) }

        item { Text("Player gestures", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Brightness6, null, tint = Accent); Spacer(Modifier.width(8.dp)); Text("Brightness sensitivity ${(brightness * 100).toInt()}%") }
                Slider(value = brightness, onValueChange = { brightness = it; prefs.brightnessSensitivity = it }, valueRange = 0.10f..0.60f)
            }
        }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.VolumeUp, null, tint = Accent); Spacer(Modifier.width(8.dp)); Text("Volume sensitivity ${(volume * 100).toInt()}%") }
                Slider(value = volume, onValueChange = { volume = it; prefs.volumeSensitivity = it }, valueRange = 0.20f..1.0f)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoRetry, onCheckedChange = { autoRetry = it; prefs.autoRetry = it })
                Spacer(Modifier.width(10.dp)); Text("Auto retry playback")
            }
        }
        item {
            OutlinedButton(onClick = { repo.clear(); onLibraryChanged(); status = "Library cleared" }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear library")
            }
        }
        item { Text("PerfectTV Enhanced v2.0", color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Panel2) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Muted)
        }
    }
}
