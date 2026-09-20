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
    val player = remember(item.id) { StreamPlayerFactory.build(context, item) }

    DisposableEffect(item.id) {
        player.setMediaItem(StreamPlayerFactory.mediaItem(item))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        onDispose {
            player.stop()
            player.release()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
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
