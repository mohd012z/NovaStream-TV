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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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
    val handler = remember { Handler(Looper.getMainLooper()) }

    val player: ExoPlayer = remember(item.id) {
        StreamPlayerFactory.build(context, item).apply {
            val resume = store.get(item.id)?.positionMs ?: 0L
            setMediaItem(StreamPlayerFactory.mediaItem(item))
            prepare()
            if (resume > 10_000) seekTo(resume)
            playWhenReady = true
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
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                message = "Playback error"
                if (prefs.autoRetry && retryCount < 3) {
                    retryCount++
                    message = "Retrying $retryCount/3…"
                    handler.postDelayed({ player.prepare(); player.playWhenReady = true }, 1200L * retryCount)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            val d = player.duration.coerceAtLeast(0L)
            val p = player.currentPosition.coerceAtLeast(0L)
            store.save(PlaybackRecord(item.id, item.name, p, d))
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
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setOnTouchListener(PlayerGestureController(activity, this, prefs.brightnessSensitivity, prefs.volumeSensitivity))
                }
            },
            update = { it.player = player }
        )

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onBack) { Text("←") }
            Text(item.name, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1)
            FilledTonalButton(onClick = { showTrackInfo = true }) { Text("Tracks") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = {
                val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                activity.enterPictureInPictureMode(params)
            }) { Text("PiP") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = {
                orientationLandscape = !orientationLandscape
                activity.requestedOrientation = if (orientationLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }) { Text(if (orientationLandscape) "Portrait" else "Landscape") }
        }

        if (message.isNotBlank()) {
            Surface(modifier = Modifier.align(Alignment.Center), color = Color.Black.copy(alpha = 0.68f), shape = MaterialTheme.shapes.medium) {
                Text(message, color = Color.White, modifier = Modifier.padding(14.dp))
            }
        }
    }

    if (showTrackInfo) {
        AlertDialog(
            onDismissRequest = { showTrackInfo = false },
            title = { Text("Audio / Subtitle / Quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Media3 adaptive track selection is active.")
                    Text("Available groups: ${player.currentTracks.groups.size}")
                    Text("Use the standard player controls for tracks supported by the stream.", color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { showTrackInfo = false }) { Text("OK") } }
        )
    }
}
