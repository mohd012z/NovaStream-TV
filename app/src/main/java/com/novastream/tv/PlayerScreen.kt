package com.novastream.tv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(item: PlaylistItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val store = remember { PlaybackStore(context) }
    val prefs = remember { PlayerPreferences(context) }
    var message by remember { mutableStateOf("Connecting…") }
    var orientationLandscape by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var showTrackInfo by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    val player: ExoPlayer = remember(item.id) {
        StreamPlayerFactory.build(context, item).apply {
            setMediaItem(StreamPlayerFactory.mediaItem(item))
            if (item.kind != MediaKind.LIVE) {
                val resume = store.get(item.id)?.positionMs ?: 0L
                if (resume > 10_000) seekTo(resume)
            }
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(showControls, message) {
        if (showControls && message.isBlank()) {
            delay(3500)
            showControls = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                message = when (state) {
                    Player.STATE_BUFFERING -> "Buffering…"
                    Player.STATE_READY -> ""
                    Player.STATE_ENDED -> "Finished"
                    else -> "Connecting…"
                }
                isPlaying = player.isPlaying
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Stream unavailable"
                if (prefs.autoRetry && retryCount < 3) {
                    retryCount++
                    message = "Retrying $retryCount/3…"
                    handler.postDelayed({
                        player.prepare()
                        player.playWhenReady = true
                    }, 900L * retryCount)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            if (item.kind != MediaKind.LIVE) {
                val d = player.duration.coerceAtLeast(0L)
                val p = player.currentPosition.coerceAtLeast(0L)
                store.save(PlaybackRecord(item.id, item.name, p, d))
            }
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setOnTouchListener(
                        PlayerGestureController(
                            activity = activity,
                            target = this,
                            brightnessSensitivity = prefs.brightnessSensitivity,
                            volumeSensitivity = prefs.volumeSensitivity,
                            onFeedback = { feedback = it },
                            onTap = { showControls = !showControls }
                        )
                    )
                }
            },
            update = { it.player = player }
        )

        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            PlayerChrome(
                item = item,
                isPlaying = isPlaying,
                onBack = onBack,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onTracks = { showTrackInfo = true },
                onPip = {
                    activity.enterPictureInPictureMode(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                    )
                },
                onRotate = {
                    orientationLandscape = !orientationLandscape
                    activity.requestedOrientation = if (orientationLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            )
        }

        if (message.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.74f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF67D6FF))
                    Spacer(Modifier.width(12.dp))
                    Text(message, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        feedback?.let { value ->
            GestureHud(value, Modifier.align(Alignment.Center))
        }
    }

    if (showTrackInfo) {
        AlertDialog(
            onDismissRequest = { showTrackInfo = false },
            title = { Text("Audio / Subtitle / Quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Adaptive Media3 track selection is active.")
                    Text("Available track groups: ${player.currentTracks.groups.size}")
                    Text("Track selector UI will show only options actually supplied by this stream.", color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { showTrackInfo = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun PlayerChrome(
    item: PlaylistItem,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onTracks: () -> Unit,
    onPip: () -> Unit,
    onRotate: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerCircleButton(Icons.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.kind == MediaKind.LIVE) "LIVE" else item.groupTitle.orEmpty(), color = Color(0xFF78F1C7), fontSize = 11.sp)
            }
            PlayerCircleButton(Icons.Filled.Tune, "Tracks", onTracks)
            Spacer(Modifier.width(8.dp))
            PlayerCircleButton(Icons.Filled.PictureInPictureAlt, "PiP", onPip)
            Spacer(Modifier.width(8.dp))
            PlayerCircleButton(Icons.Filled.ScreenRotation, "Rotate", onRotate)
        }

        Box(
            Modifier.align(Alignment.Center).size(72.dp).background(Color.Black.copy(alpha = .55f), CircleShape)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(42.dp)
            )
        }

        if (item.kind == MediaKind.LIVE) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("LIVE", color = Color.White, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text("Swipe left: brightness • right: volume", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PlayerCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Box(
        Modifier.size(46.dp).background(Color(0xFF1B2430).copy(alpha = .88f), CircleShape).clickable(onClick = action),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(23.dp)) }
}

@Composable
private fun GestureHud(feedback: GestureFeedback, modifier: Modifier = Modifier) {
    val isBrightness = feedback is GestureFeedback.Brightness
    val percent = when (feedback) {
        is GestureFeedback.Brightness -> feedback.percent
        is GestureFeedback.Volume -> feedback.percent
    }
    Surface(modifier, color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isBrightness) Icons.Filled.Brightness6 else Icons.Filled.VolumeUp,
                null,
                tint = Color(0xFF67D6FF),
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("$percent%", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(if (isBrightness) "Brightness" else "Volume", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
        }
    }
}
