package com.novastream.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ShortsAccent = Color(0xFF67D6FF)

// TikTok/Reels-style vertical feed of Short Drama episodes. Built from the
// full library's SHORT_DRAMA items, grouped into shows via the existing
// groupItemsByShow() helper and flattened into one continuous playback
// queue: shows are shuffled (a light "discover something new" heuristic
// with no watch-history signal to personalize on here), and within each
// show its episodes stay in order so swiping forward inside a show plays
// the next episode, not a random one.
@Composable
fun ShortDramaFeedScreen(
    playlist: List<PlaylistItem>,
    onBack: () -> Unit,
    onOpenMedia: (PlaylistItem) -> Unit = {}
) {
    val queue = remember(playlist) { buildShortDramaQueue(playlist) }
    val pagerState = rememberPagerState(pageCount = { queue.size })
    val scope = rememberCoroutineScope()
    var countdownPage by remember { mutableIntStateOf(-1) }
    var countdown by remember { mutableIntStateOf(0) }
    var completedItem by remember { mutableStateOf<PlaylistItem?>(null) }
    val context = LocalContext.current
    val playbackStore = remember(context) { PlaybackStore(context) }
    val continueWatching = remember(completedItem) {
        playbackStore.recent(8).mapNotNull { record ->
            playlist.firstOrNull { it.id == record.id && it.kind != MediaKind.SHORT_DRAMA && it.kind != MediaKind.LIVE }
                ?.let { it to record }
        }.take(3)
    }
    val moviePick = remember(completedItem, playlist) { playlist.firstOrNull { it.kind == MediaKind.MOVIE } }
    val seriesPick = remember(completedItem, playlist) { playlist.firstOrNull { it.kind == MediaKind.SERIES } }
    val livePick = remember(completedItem, playlist) { playlist.firstOrNull { it.kind == MediaKind.LIVE } }

    LaunchedEffect(countdownPage, countdown) {
        if (countdownPage >= 0 && countdown > 0) {
            delay(1_000L)
            // Do not force-scroll if the viewer already swiped away manually.
            if (pagerState.currentPage != countdownPage) {
                countdown = 0
                countdownPage = -1
            } else if (countdown > 1) {
                countdown -= 1
            } else {
                val target = countdownPage + 1
                countdown = 0
                countdownPage = -1
                if (target < queue.size) pagerState.animateScrollToPage(target)
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        // A manual swipe cancels stale end-of-episode UI from the previous page.
        if (countdownPage >= 0 && pagerState.currentPage != countdownPage) {
            countdown = 0
            countdownPage = -1
        }
        completedItem = null
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (queue.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Movie, null, tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No short dramas in your library yet", color = Color.White)
            }
        } else {
            // Adjacent pages may stay composed for smooth swiping, but only the
            // current page is allowed to own a live ExoPlayer.
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                ShortDramaPage(
                    item = queue[page],
                    isActive = pagerState.currentPage == page,
                    onEnded = {
                        if (page == pagerState.currentPage) {
                            val current = queue[page]
                            val next = queue.getOrNull(page + 1)
                            val sameSeries = UniversalPlaybackPolicy.isSameSeries(current, next)
                            val decision = UniversalPlaybackPolicy.onEnded(
                                item = current,
                                hasNextEpisode = sameSeries,
                                hasNextQueueItem = next != null
                            )
                            when (decision.action) {
                                PlaybackEndAction.NEXT_EPISODE -> {
                                    countdownPage = page
                                    countdown = 3
                                }
                                PlaybackEndAction.SERIES_COMPLETE,
                                PlaybackEndAction.SHOW_WHATS_NEXT,
                                PlaybackEndAction.MOVIE_COMPLETE -> completedItem = current
                                PlaybackEndAction.NEXT_QUEUE_ITEM -> {
                                    if (next != null) scope.launch { pagerState.animateScrollToPage(page + 1) }
                                }
                                PlaybackEndAction.KEEP_LIVE -> Unit
                            }
                        }
                    }
                )
            }
        }

        if (countdownPage == pagerState.currentPage && countdown > 0) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.Black.copy(alpha = .82f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Next episode in $countdown", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        countdownPage = -1
                        countdown = 0
                    }) { Text("Cancel", color = ShortsAccent) }
                }
            }
        }

        completedItem?.let { finished ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.Black.copy(alpha = .9f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CheckCircle, null, tint = ShortsAccent, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Series complete", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(finished.showName ?: finished.name, color = Color.White.copy(alpha = .72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (continueWatching.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Continue Watching", color = Color.White, fontWeight = FontWeight.SemiBold)
                        continueWatching.forEach { (media, record) ->
                            TextButton(onClick = { onOpenMedia(media) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    media.name + " • " + (record.progress * 100).toInt() + "%",
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        moviePick?.let { movie ->
                            TextButton(onClick = { onOpenMedia(movie) }) { Text("Movie", color = ShortsAccent) }
                        }
                        seriesPick?.let { series ->
                            TextButton(onClick = { onOpenMedia(series) }) { Text("Series", color = ShortsAccent) }
                        }
                        livePick?.let { live ->
                            TextButton(onClick = { onOpenMedia(live) }) { Text("Live TV", color = ShortsAccent) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            completedItem = null
                            if (pagerState.currentPage < queue.lastIndex) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        }) { Text("Another Short", color = ShortsAccent) }
                        TextButton(onClick = onBack) { Text("Home", color = Color.White) }
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(10.dp)
                .background(Color.Black.copy(alpha = .45f), CircleShape)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
    }

}

@Composable
private fun ShortAppearanceDialog(
    value: ShortAppearance,
    onChange: (ShortAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    var fontMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shorts appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Title: ${value.titleSizeSp.toInt()} sp")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onChange(value.copy(titleSizeSp = (value.titleSizeSp - 1f).coerceAtLeast(10f))) }) { Text("−") }
                    Slider(value = value.titleSizeSp, onValueChange = { onChange(value.copy(titleSizeSp = it)) }, valueRange = 10f..72f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    OutlinedButton(onClick = { onChange(value.copy(titleSizeSp = (value.titleSizeSp + 1f).coerceAtMost(72f))) }) { Text("+") }
                }
                Text("Episode/info: ${value.infoSizeSp.toInt()} sp")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onChange(value.copy(infoSizeSp = (value.infoSizeSp - 1f).coerceAtLeast(10f))) }) { Text("−") }
                    Slider(value = value.infoSizeSp, onValueChange = { onChange(value.copy(infoSizeSp = it)) }, valueRange = 10f..72f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    OutlinedButton(onClick = { onChange(value.copy(infoSizeSp = (value.infoSizeSp + 1f).coerceAtMost(72f))) }) { Text("+") }
                }
                Box {
                    OutlinedButton(onClick = { fontMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Font: " + value.fontFamily.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                    }
                    DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                        ShortFontFamily.entries.forEach { family ->
                            DropdownMenuItem(
                                text = { Text(family.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { fontMenu = false; onChange(value.copy(fontFamily = family)) }
                            )
                        }
                    }
                }
                Text("Text color")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFF80DEEA, 0xFFA5D6A7).forEach { argb ->
                        Box(
                            Modifier.size(34.dp)
                                .background(Color(argb.toULong()), CircleShape)
                                .clickable { onChange(value.copy(textColorArgb = argb)) }
                        )
                    }
                }
                Text("Changes are saved automatically • 10–72 sp", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

private fun shortFontFamily(value: ShortFontFamily): FontFamily = when (value) {
    ShortFontFamily.SYSTEM, ShortFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ShortFontFamily.SERIF -> FontFamily.Serif
    ShortFontFamily.MONOSPACE -> FontFamily.Monospace
}

private fun buildShortDramaQueue(playlist: List<PlaylistItem>): List<PlaylistItem> {
    val shortDramas = playlist.filter { it.kind == MediaKind.SHORT_DRAMA }
    val shows = groupItemsByShow(shortDramas).shuffled()
    return shows.flatMap { show ->
        show.items.sortedWith(compareBy({ it.episodeNumber ?: Int.MAX_VALUE }, { it.name }))
    }
}

@Composable
private fun ShortDramaPage(item: PlaylistItem, isActive: Boolean, onEnded: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlaybackStore(context) }
    var isPlaying by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var bufferingSinceMs by remember { mutableLongStateOf(0L) }
    var lastKnownPositionMs by remember { mutableLongStateOf(0L) }
    var lastProgressAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var playbackHealth by remember { mutableStateOf(PlaybackHealth.CONNECTING) }
    val appearancePrefs = remember { ShortAppearancePreferences(context) }
    var appearance by remember { mutableStateOf(appearancePrefs.load()) }

    // Only the active pager page owns a player. Adjacent pages remain cheap UI
    // placeholders, preventing multiple ExoPlayer instances from buffering at once.
    val player: ExoPlayer? = if (isActive) {
        remember(item.id) { StreamPlayerFactory.build(context, item) }
    } else null

    DisposableEffect(player, item.id) {
        if (player != null) {
            player.setMediaItem(StreamPlayerFactory.mediaItem(item))
            store.get(item.id)?.positionMs?.takeIf { it > 3_000L }?.let(player::seekTo)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            if (player != null) {
                val duration = player.duration.coerceAtLeast(0L)
                val position = player.currentPosition.coerceAtLeast(0L)
                if (position > 0L) store.save(PlaybackRecord(item.id, item.name, position, duration))
                player.release()
            }
        }
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackHealth = when (state) {
                    Player.STATE_BUFFERING -> {
                        if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
                        PlaybackHealth.BUFFERING
                    }
                    Player.STATE_READY -> {
                        bufferingSinceMs = 0L
                        showRecovery = false
                        retryCount = 0
                        lastKnownPositionMs = player.currentPosition.coerceAtLeast(0L)
                        lastProgressAtMs = System.currentTimeMillis()
                        PlaybackHealth.READY
                    }
                    Player.STATE_ENDED -> PlaybackHealth.ENDED
                    else -> PlaybackHealth.CONNECTING
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackHealth = PlaybackHealth.RECOVERING
                showRecovery = true
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, playbackHealth, bufferingSinceMs) {
        if (player != null && playbackHealth == PlaybackHealth.BUFFERING && bufferingSinceMs > 0L) {
            delay(10_000L)
            if (player.playbackState == Player.STATE_BUFFERING && retryCount < 3) {
                val resumeAt = player.currentPosition.coerceAtLeast(0L)
                retryCount += 1
                playbackHealth = PlaybackHealth.RECOVERING
                showRecovery = true
                player.prepare()
                if (resumeAt > 0L) player.seekTo(resumeAt)
                player.playWhenReady = true
            } else if (player.playbackState == Player.STATE_BUFFERING) {
                playbackHealth = PlaybackHealth.ERROR
                showRecovery = true
            }
        }
    }

    LaunchedEffect(player) {
        while (player != null) {
            delay(2_000L)
            val now = System.currentTimeMillis()
            val position = player.currentPosition.coerceAtLeast(0L)
            if (position > lastKnownPositionMs + 250L) {
                lastKnownPositionMs = position
                lastProgressAtMs = now
            }
            if (position > 0L) {
                store.save(PlaybackRecord(item.id, item.name, position, player.duration.coerceAtLeast(0L)))
            }

            // Some broken/overloaded sources stop advancing without producing a
            // PlayerException or a long BUFFERING state. Detect that silent stall
            // while playWhenReady is still true and recover at the same timestamp.
            if (
                player.playWhenReady &&
                player.playbackState == Player.STATE_READY &&
                !player.isPlaying &&
                now - lastProgressAtMs >= 8_000L
            ) {
                val resumeAt = position
                if (retryCount < 3) {
                    retryCount += 1
                    playbackHealth = PlaybackHealth.RECOVERING
                    showRecovery = true
                    player.prepare()
                    if (resumeAt > 0L) player.seekTo(resumeAt)
                    player.playWhenReady = true
                    lastProgressAtMs = now
                } else {
                    playbackHealth = PlaybackHealth.ERROR
                    showRecovery = true
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                    }
                },
                update = { it.player = player }
            )

            Box(
                Modifier.fillMaxSize().clickable {
                    if (player.isPlaying) player.pause() else player.play()
                }
            )
        }

        if (showRecovery && player != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.Black.copy(alpha = .82f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (playbackHealth == PlaybackHealth.ERROR) "Playback interrupted" else "Recovering stream…",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Attempt $retryCount/3 • progress preserved", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = {
                        val resumeAt = player.currentPosition.coerceAtLeast(0L)
                        retryCount = (retryCount + 1).coerceAtMost(3)
                        playbackHealth = PlaybackHealth.RECOVERING
                        player.prepare()
                        if (resumeAt > 0L) player.seekTo(resumeAt)
                        player.playWhenReady = true
                    }) { Text("Retry now", color = ShortsAccent) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(18.dp).fillMaxWidth(.72f)
        ) {
            Text(
                item.showName?.takeIf { it.isNotBlank() } ?: item.name,
                color = Color(appearance.textColorArgb.toULong()),
                fontFamily = shortFontFamily(appearance.fontFamily),
                fontWeight = FontWeight.Black,
                fontSize = appearance.titleSizeSp.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            item.episodeNumber?.let {
                Text(
                    "Episode $it",
                    color = Color(appearance.infoColorArgb.toULong()),
                    fontFamily = shortFontFamily(appearance.fontFamily),
                    fontSize = appearance.infoSizeSp.sp
                )
            }
        }

        if (player != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconButton(
                    onClick = { showAppearance = true },
                    modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = .45f), CircleShape)
                ) { Icon(Icons.Filled.TextFields, "Text appearance", tint = Color.White) }

                IconButton(
                    onClick = {
                        isMuted = !isMuted
                        player.volume = if (isMuted) 0f else 1f
                    },
                    modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = .45f), CircleShape)
                ) { Icon(if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Mute", tint = Color.White) }

                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = .45f), CircleShape)
                ) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.White) }
            }
        }
    }

    if (showAppearance) {
        ShortAppearanceDialog(
            value = appearance,
            onChange = {
                appearance = it
                appearancePrefs.save(it)
            },
            onDismiss = { showAppearance = false }
        )
    }
}
