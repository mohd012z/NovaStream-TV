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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
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
fun ShortDramaFeedScreen(playlist: List<PlaylistItem>, onBack: () -> Unit) {
    val queue = remember(playlist) { buildShortDramaQueue(playlist) }
    val pagerState = rememberPagerState(pageCount = { queue.size })
    val scope = rememberCoroutineScope()

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
            // beyondViewportPageCount = 1 keeps at most the current page plus one
            // adjacent page composed (and therefore holding a live ExoPlayer) at
            // any time; pages further away are disposed, releasing their player.
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                ShortDramaPage(
                    item = queue[page],
                    isActive = pagerState.currentPage == page,
                    onEnded = {
                        if (page == pagerState.currentPage && page < queue.lastIndex) {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    }
                )
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
    var isPlaying by remember(item.id) { mutableStateOf(true) }
    var isMuted by remember(item.id) { mutableStateOf(false) }
    var playbackHealth by remember(item.id) { mutableStateOf(PlaybackHealth.CONNECTING) }
    var retryCount by remember(item.id) { mutableIntStateOf(0) }
    var bufferingSinceMs by remember(item.id) { mutableLongStateOf(0L) }
    var showRecovery by remember(item.id) { mutableStateOf(false) }
    val store = remember { PlaybackStore(context) }
    val player = remember(item.id) { StreamPlayerFactory.build(context, item) }

    DisposableEffect(item.id) {
        player.setMediaItem(StreamPlayerFactory.mediaItem(item))
        store.get(item.id)?.positionMs?.takeIf { it > 3_000L }?.let { player.seekTo(it) }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        onDispose {
            val duration = player.duration.coerceAtLeast(0L)
            val position = player.currentPosition.coerceAtLeast(0L)
            store.save(PlaybackRecord(item.id, item.name, position, duration))
            player.stop()
            player.release()
        }
    }

    DisposableEffect(player) {
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

    LaunchedEffect(isActive) {
        if (isActive) {
            player.playWhenReady = true
            player.play()
        } else {
            player.pause()
        }
    }

    // A short should never sit forever on a frozen frame. If Media3 remains
    // BUFFERING for 10 seconds, try a bounded recovery from the same position.
    LaunchedEffect(isActive, playbackHealth, bufferingSinceMs) {
        if (isActive && playbackHealth == PlaybackHealth.BUFFERING && bufferingSinceMs > 0L) {
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

    LaunchedEffect(isActive) {
        while (isActive) {
            delay(2_000L)
            val duration = player.duration.coerceAtLeast(0L)
            val position = player.currentPosition.coerceAtLeast(0L)
            if (position > 0L) store.save(PlaybackRecord(item.id, item.name, position, duration))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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

        // Full-screen tap toggles play/pause; the mute/play buttons below sit
        // on top of it (declared after, so they win hit-testing) and still work.
        Box(
            Modifier.fillMaxSize().clickable {
                if (player.isPlaying) player.pause() else player.play()
            }
        )

        if (showRecovery) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.Black.copy(alpha = .82f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (playbackHealth) {
                            PlaybackHealth.ERROR -> "Playback interrupted"
                            else -> "Recovering stream…"
                        },
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
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(18.dp)
                .fillMaxWidth(.72f)
        ) {
            Text(
                item.showName?.takeIf { it.isNotBlank() } ?: item.name,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            item.episodeNumber?.let {
                Text("Episode $it", color = ShortsAccent, fontSize = 13.sp)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconButton(
                onClick = {
                    isMuted = !isMuted
                    player.volume = if (isMuted) 0f else 1f
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = .45f), CircleShape)
            ) {
                Icon(if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Mute", tint = Color.White)
            }
            IconButton(
                onClick = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = .45f), CircleShape)
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.White)
            }
        }
    }
}
