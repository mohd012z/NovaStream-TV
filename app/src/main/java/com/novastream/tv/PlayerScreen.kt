package com.novastream.tv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.Surface
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(item: PlaylistItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val store = remember { PlaybackStore(context) }
    val prefs = remember { PlayerPreferences(context) }
    val probeCache = remember { NovaProbeCache(context) }
    var message by remember { mutableStateOf("Connecting…") }
    var bufferingSinceMs by remember { mutableLongStateOf(0L) }
    var firstFrameMs by remember { mutableLongStateOf(0L) }
    var stallRecoveryCount by remember { mutableIntStateOf(0) }
    var lastProgressPositionMs by remember { mutableLongStateOf(0L) }
    var lastProgressAtMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var lastNetworkSnapshot by remember { mutableStateOf(builtNetworkSnapshotPlaceholder()) }
    var recoveryQualityStep by remember { mutableIntStateOf(0) }
    val startupStartedMs = remember(item.id) { android.os.SystemClock.elapsedRealtime() }
    var orientationLandscape by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var selectedQualityLabel by remember { mutableStateOf("Auto") }
    var showControls by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var previousVolume by remember { mutableFloatStateOf(1f) }
    var videoInfo by remember { mutableStateOf("Auto quality") }
    var signalInfo by remember { mutableStateOf("Adaptive") }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var showAudioEffects by remember { mutableStateOf(false) }
    var audioPreset by remember { mutableStateOf(prefs.audioPreset) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val audioEffects = remember(item.id) { mutableStateOf<AudioEffectsController?>(null) }

    val built = remember(item.id) { StreamPlayerFactory.buildAdaptive(context, item) }
    // Initialize the network baseline after the player exists.
    if (lastNetworkSnapshot.atMs == 0L) lastNetworkSnapshot = built.networkStats.snapshot()
    val player: ExoPlayer = built.player
    LaunchedEffect(player, item.id) {
        // Reuse what NovaStream learned from earlier stalls on this exact endpoint.
        // A stream that repeatedly freezes at 1080p starts conservatively next time.
        probeCache.get(item.streamUrl)?.preferredMaxHeight
            ?.takeIf { it != Int.MAX_VALUE && it > 0 }
            ?.let { learnedHeight ->
                built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, learnedHeight)
                    .setForceHighestSupportedBitrate(false)
                    .build()
                selectedQualityLabel = "Auto • learned ${learnedHeight}p"
            }
        player.setMediaItem(StreamPlayerFactory.mediaItem(item))
        if (item.kind != MediaKind.LIVE) {
            val resume = store.get(item.id)?.positionMs ?: 0L
            if (resume > 10_000) player.seekTo(resume)
        }
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(showControls, message) {
        if (showControls && message.isBlank()) {
            delay(3500)
            showControls = false
        }
    }

    // Buffer watchdog: do not let live IPTV sit behind a generic spinner forever.
    // It reports the stage first; source errors still use the normal retry ladder below.
    LaunchedEffect(message, bufferingSinceMs) {
        if (message.startsWith("Buffering") && bufferingSinceMs > 0L) {
            delay(3_000)
            if (player.playbackState == Player.STATE_BUFFERING) message = "Buffering • checking stream…"
            delay(3_000)
            if (player.playbackState == Player.STATE_BUFFERING) message = "Slow stream • adapting quality…"
            delay(4_000)
            if (player.playbackState == Player.STATE_BUFFERING) {
                message = "Stream is taking longer than expected…"
                if ((item.kind == MediaKind.LIVE || item.kind == MediaKind.UNKNOWN) && stallRecoveryCount < 1) {
                    stallRecoveryCount++
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                    message = "Recovering live stream…"
                }
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val networkNow = built.networkStats.snapshot()
            val networkMbps = networkNow.mbpsSince(lastNetworkSnapshot)
            val networkSilentMs = built.networkStats.ageSinceLastByteMs(nowMs)
            lastNetworkSnapshot = networkNow
            if (currentPositionMs > lastProgressPositionMs + 250L) {
                lastProgressPositionMs = currentPositionMs
                lastProgressAtMs = nowMs
            } else if (player.playWhenReady && player.playbackState == Player.STATE_READY &&
                nowMs - lastProgressAtMs >= 6_000L && stallRecoveryCount < 2
            ) {
                // READY-but-frozen is different from normal buffering: data/decoder can
                // stall without generating onPlayerError. Re-prepare at the last good point.
                stallRecoveryCount++
                val resumeAt = currentPositionMs
                // If data is still arriving but frames stop advancing, prefer a lower
                // rendition before re-preparing. If bytes also stopped, this is a source/
                // network stall and re-prepare is the useful recovery.
                if (networkSilentMs < 2_500L && recoveryQualityStep < 2) {
                    recoveryQualityStep++
                    val maxHeight = if (recoveryQualityStep == 1) 720 else 480
                    built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                        .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                        .setForceHighestSupportedBitrate(false)
                        .build()
                    message = "Video stalled • lowering to ${maxHeight}p…"
                    probeCache.markStall(item.streamUrl, maxHeight)
                } else {
                    probeCache.markStall(item.streamUrl, if (recoveryQualityStep > 0) 480 else 720)
                    message = if (networkMbps <= 0.01) "Source stalled • reconnecting…" else "Playback stalled • recovering…"
                }
                player.prepare()
                if (item.kind == MediaKind.LIVE || item.kind == MediaKind.UNKNOWN) {
                    player.seekToDefaultPosition()
                } else if (resumeAt > 0L) {
                    player.seekTo((resumeAt - 1_000L).coerceAtLeast(0L))
                }
                player.playWhenReady = true
                lastProgressAtMs = nowMs
            }
            if (audioEffects.value == null && player.audioSessionId != 0) {
                val controller = runCatching { AudioEffectsController(player.audioSessionId) }.getOrNull()
                controller?.apply(audioPreset)
                audioEffects.value = controller
            }
            delay(500)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                message = when (state) {
                    Player.STATE_BUFFERING -> {
                        if (bufferingSinceMs == 0L) bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                        "Buffering…"
                    }
                    Player.STATE_READY -> {
                        bufferingSinceMs = 0L
                        ""
                    }
                    Player.STATE_ENDED -> "Finished"
                    else -> "Connecting…"
                }
                isPlaying = player.isPlaying
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onRenderedFirstFrame() {
                if (firstFrameMs == 0L) {
                    firstFrameMs = android.os.SystemClock.elapsedRealtime() - startupStartedMs
                    val firstByte = built.networkStats.firstByteDelayMs()
                    val start = if (firstFrameMs < 1000) "${firstFrameMs} ms" else String.format("%.1f s", firstFrameMs / 1000f)
                    signalInfo = if (firstByte >= 0) "Start $start • first data ${firstByte}ms" else "Started in $start"
                    probeCache.observe(
                        item = item,
                        isLive = player.isCurrentMediaItemLive,
                        isSeekable = player.isCurrentMediaItemSeekable,
                        startupMs = firstFrameMs,
                        firstByteMs = firstByte,
                        healthy = true
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val video = tracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).asSequence().filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }
                    .firstOrNull()
                if (video != null) {
                    val size = if (video.height > 0) "${video.height}p" else "Auto"
                    val fps = if (video.frameRate > 0) " • ${video.frameRate.toInt()} fps" else ""
                    videoInfo = size + fps
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                probeCache.markFailure(item.streamUrl)
                message = "Stream unavailable"
                if (prefs.autoRetry && retryCount < 3) {
                    retryCount++
                    message = "Retrying $retryCount/3…"
                    handler.postDelayed({
                        if (item.kind == MediaKind.LIVE || item.kind == MediaKind.UNKNOWN) player.seekToDefaultPosition()
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
            audioEffects.value?.release()
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { onBack() }

    fun seekBy(deltaMs: Long) {
        if (item.kind != MediaKind.LIVE && player.isCurrentMediaItemSeekable) {
            val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
        }
    }

    fun seekTo(positionMs: Long) {
        if (item.kind != MediaKind.LIVE && player.isCurrentMediaItemSeekable) {
            player.seekTo(positionMs.coerceIn(0L, player.duration.coerceAtLeast(0L)))
            currentPositionMs = positionMs
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
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setOnTouchListener(
                        PlayerGestureController(
                            activity = activity,
                            target = this,
                            brightnessSensitivity = prefs.brightnessSensitivity,
                            volumeSensitivity = prefs.volumeSensitivity,
                            canSeek = { item.kind != MediaKind.LIVE && player.isCurrentMediaItemSeekable },
                            onSeek = { delta -> seekBy(delta) },
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
                videoInfo = videoInfo,
                signalInfo = signalInfo,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onSeekTo = { seekTo(it) },
                onBack = onBack,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeekBack = { seekBy(-10_000) },
                onSeekForward = { seekBy(10_000) },
                isMuted = isMuted,
                onMute = {
                    if (isMuted) {
                        player.volume = previousVolume.coerceAtLeast(.15f)
                        isMuted = false
                    } else {
                        previousVolume = player.volume
                        player.volume = 0f
                        isMuted = true
                    }
                },
                onSubtitles = { showSubtitles = true },
                onAudio = { showAudioTracks = true },
                onSound = { showAudioEffects = true },
                onSpeed = { showSpeed = true },
                onQuality = { showQuality = true },
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

    if (showQuality) {
        val options = listOf("Auto" to Int.MAX_VALUE, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p • Data saver" to 360)
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("Video quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Auto adapts to your connection and is recommended.", color = Color.Gray, fontSize = 12.sp)
                    options.forEach { (label, height) ->
                        TextButton(
                            onClick = {
                                built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                                    .setMaxVideoSize(Int.MAX_VALUE, height)
                                    .setForceHighestSupportedBitrate(height != Int.MAX_VALUE)
                                    .build()
                                selectedQualityLabel = label
                                showQuality = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, modifier = Modifier.weight(1f))
                                if (selectedQualityLabel == label) Icon(Icons.Filled.Check, null, tint = Color(0xFF67D6FF))
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showSpeed) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("Playback speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    speeds.forEach { speed ->
                        TextButton(
                            onClick = { player.setPlaybackSpeed(speed); showSpeed = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (speed == 1f) "Normal (1×)" else "${speed}×") }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showSubtitles) {
        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        AlertDialog(
            onDismissRequest = { showSubtitles = false },
            title = { Text("Subtitles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .build()
                            showSubtitles = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Off") }
                    if (textGroups.isEmpty()) {
                        Text("This stream doesn't provide any subtitle tracks.", color = Color.Gray, fontSize = 12.sp)
                    }
                    textGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val label = format.label ?: format.language?.uppercase() ?: "Subtitle"
                            TextButton(
                                onClick = {
                                    built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    showSubtitles = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showAudioTracks) {
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        AlertDialog(
            onDismissRequest = { showAudioTracks = false },
            title = { Text("Audio track") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .build()
                            showAudioTracks = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Auto") }
                    if (audioGroups.isEmpty()) {
                        Text("This stream doesn't expose separate audio tracks to choose from.", color = Color.Gray, fontSize = 12.sp)
                    }
                    audioGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val mime = format.codecs ?: format.sampleMimeType.orEmpty()
                            val surround = when {
                                mime.contains("ac-4", true) || mime.contains("atmos", true) -> " • Dolby Atmos"
                                mime.contains("eac3", true) || mime.contains("ec-3", true) -> " • Dolby Digital Plus"
                                mime.contains("ac-3", true) -> " • Dolby Digital"
                                format.channelCount >= 6 -> " • ${format.channelCount}ch surround"
                                else -> ""
                            }
                            val base = format.label ?: format.language?.uppercase() ?: "Track ${i + 1}"
                            TextButton(
                                onClick = {
                                    built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    showAudioTracks = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(base + surround) }
                        }
                    }
                    Text(
                        "Dolby/Atmos options only appear when the stream itself provides that audio track and the device supports passthrough.",
                        color = Color.Gray, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (showAudioEffects) {
        AlertDialog(
            onDismissRequest = { showAudioEffects = false },
            title = { Text("Sound") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Built-in audio effects applied to this device's output. These enhance the existing stereo signal - they don't add channels a stream doesn't have.",
                        color = Color.Gray, fontSize = 12.sp
                    )
                    AudioPreset.entries.forEach { preset ->
                        TextButton(
                            onClick = {
                                audioPreset = preset
                                prefs.audioPreset = preset
                                audioEffects.value?.apply(preset)
                                showAudioEffects = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(preset.label, modifier = Modifier.weight(1f))
                                if (audioPreset == preset) Icon(Icons.Filled.Check, null, tint = Color(0xFF67D6FF))
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun PlayerChrome(
    item: PlaylistItem,
    isPlaying: Boolean,
    videoInfo: String,
    signalInfo: String,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    isMuted: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onMute: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onSound: () -> Unit,
    onSpeed: () -> Unit,
    onQuality: () -> Unit,
    onPip: () -> Unit,
    onRotate: () -> Unit
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableFloatStateOf(0f) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val seekable = item.kind != MediaKind.LIVE && durationMs > 0
    val displayedPositionMs = if (isSeeking) seekPreviewMs.toLong() else currentPositionMs

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerCircleButton(Icons.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (item.kind == MediaKind.LIVE) "LIVE" else item.groupTitle.orEmpty()) + " • " + videoInfo + " • " + signalInfo,
                    color = Color(0xFF78F1C7), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            if (item.kind != MediaKind.LIVE) {
                PlayerCircleButton(Icons.Filled.Replay10, "Rewind 10 seconds", onSeekBack, size = 56.dp, iconSize = 28.dp)
            }
            Box(
                Modifier.size(72.dp).background(Color.Black.copy(alpha = .55f), CircleShape)
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
            if (item.kind != MediaKind.LIVE) {
                PlayerCircleButton(Icons.Filled.Forward10, "Forward 10 seconds", onSeekForward, size = 56.dp, iconSize = 28.dp)
            }
        }

        if (item.kind == MediaKind.LIVE) {
            Row(
                Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("LIVE", color = Color.White, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text("Swipe left: brightness • right: volume", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box {
                    PlayerCircleButton(Icons.Filled.Settings, "Playback settings", { settingsExpanded = true })
                    DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute" else "Mute") },
                            leadingIcon = { Icon(if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, null) },
                            onClick = { settingsExpanded = false; onMute() }
                        )
                        DropdownMenuItem(
                            text = { Text("Subtitles") },
                            leadingIcon = { Icon(Icons.Filled.ClosedCaption, null) },
                            onClick = { settingsExpanded = false; onSubtitles() }
                        )
                        DropdownMenuItem(
                            text = { Text("Audio track") },
                            leadingIcon = { Icon(Icons.Filled.Audiotrack, null) },
                            onClick = { settingsExpanded = false; onAudio() }
                        )
                        DropdownMenuItem(
                            text = { Text("Sound") },
                            leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                            onClick = { settingsExpanded = false; onSound() }
                        )
                        DropdownMenuItem(
                            text = { Text("Video quality") },
                            leadingIcon = { Icon(Icons.Filled.HighQuality, null) },
                            onClick = { settingsExpanded = false; onQuality() }
                        )
                        if (item.kind != MediaKind.LIVE) {
                            DropdownMenuItem(
                                text = { Text("Playback speed") },
                                leadingIcon = { Icon(Icons.Filled.Speed, null) },
                                onClick = { settingsExpanded = false; onSpeed() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Mini player") },
                            leadingIcon = { Icon(Icons.Filled.PictureInPictureAlt, null) },
                            onClick = { settingsExpanded = false; onPip() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rotate") },
                            leadingIcon = { Icon(Icons.Filled.ScreenRotation, null) },
                            onClick = { settingsExpanded = false; onRotate() }
                        )
                    }
                }
            }

            if (seekable) {
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = displayedPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { isSeeking = true; seekPreviewMs = it },
                    onValueChangeFinished = { onSeekTo(seekPreviewMs.toLong()); isSeeking = false },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF67D6FF), activeTrackColor = Color(0xFF67D6FF))
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(displayedPositionMs), color = Color.White, fontSize = 12.sp)
                    Text("-" + formatPlaybackTime((durationMs - displayedPositionMs).coerceAtLeast(0)), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun builtNetworkSnapshotPlaceholder() =
    StreamNetworkStats.NetworkSnapshot(0L, 0L, 0L)

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PlayerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    action: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    iconSize: androidx.compose.ui.unit.Dp = 23.dp
) {
    Box(
        Modifier.size(size).background(Color(0xFF1B2430).copy(alpha = .88f), CircleShape).clickable(onClick = action),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(iconSize)) }
}

@Composable
private fun GestureHud(feedback: GestureFeedback, modifier: Modifier = Modifier) {
    val isBrightness = feedback is GestureFeedback.Brightness
    val percent = when (feedback) {
        is GestureFeedback.Brightness -> feedback.percent
        is GestureFeedback.Volume -> feedback.percent
        is GestureFeedback.Seek -> feedback.seconds
    }
    Surface(modifier, color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                when (feedback) {
                    is GestureFeedback.Brightness -> Icons.Filled.Brightness6
                    is GestureFeedback.Volume -> Icons.Filled.VolumeUp
                    is GestureFeedback.Seek -> if (feedback.forward) Icons.Filled.FastForward else Icons.Filled.FastRewind
                },
                null,
                tint = Color(0xFF67D6FF),
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (feedback is GestureFeedback.Seek) "${if (feedback.forward) "+" else "-"}${percent}s" else "$percent%",
                color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp
            )
            Text(
                when (feedback) {
                    is GestureFeedback.Brightness -> "Brightness"
                    is GestureFeedback.Volume -> "Volume"
                    is GestureFeedback.Seek -> if (feedback.forward) "Forward" else "Rewind"
                },
                color = Color.White.copy(alpha = .7f), fontSize = 11.sp
            )
        }
    }
}
