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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
    var hasPlayedOnce by remember(item.id) { mutableStateOf(false) }
    var rebufferStartedAtMs by remember(item.id) { mutableLongStateOf(0L) }
    var lastRecoveryAtMs by remember(item.id) { mutableLongStateOf(0L) }
    var lastProgressPositionMs by remember(item.id) { mutableLongStateOf(0L) }
    var lastProgressAtMs by remember(item.id) { mutableLongStateOf(System.currentTimeMillis()) }
    var adaptiveLimitBps by remember(item.id) { mutableIntStateOf(0) }
    var lastAdaptiveAtMs by remember(item.id) { mutableLongStateOf(0L) }
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
    var showTrace by remember { mutableStateOf(false) }
    var trace by remember(item.id) { mutableStateOf(PlaybackTraceSnapshot(sourceHost = runCatching { java.net.URI(item.streamUrl).host ?: "" }.getOrDefault(""), protocol = when { item.streamUrl.substringBefore('?').endsWith(".m3u8", true) -> "HLS"; item.streamUrl.substringBefore('?').endsWith(".mpd", true) -> "DASH"; item.streamUrl.substringBefore('?').endsWith(".mp4", true) -> "MP4"; else -> "AUTO" })) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var showAudioEffects by remember { mutableStateOf(false) }
    var audioPreset by remember { mutableStateOf(prefs.audioPreset) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val audioEffects = remember(item.id) { mutableStateOf<AudioEffectsController?>(null) }

    val built = remember(item.id) { StreamPlayerFactory.buildAdaptive(context, item) }
    val player: ExoPlayer = built.player
    LaunchedEffect(player, item.id) {
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

    LaunchedEffect(player) {
        while (true) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            trace = trace.copy(positionMs = currentPositionMs, bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L), durationMs = durationMs, retryCount = retryCount)
            val now = System.currentTimeMillis()
            if (currentPositionMs > lastProgressPositionMs + 250L) {
                lastProgressPositionMs = currentPositionMs
                lastProgressAtMs = now
            }
            val silentStall = player.playWhenReady && player.playbackState == Player.STATE_READY &&
                !player.isPlaying && now - lastProgressAtMs >= 8_000L
            val decision = RecoveryBrain.decide(trace)
            if (decision.action == RecoveryAction.REDUCE_QUALITY &&
                trace.bandwidthEstimateBps > 0L && now - lastAdaptiveAtMs >= 12_000L) {
                val safeLimit = (trace.bandwidthEstimateBps * 70L / 100L)
                    .coerceIn(250_000L, 20_000_000L).toInt()
                if (adaptiveLimitBps == 0 || safeLimit < adaptiveLimitBps) {
                    built.trackSelector.parameters = built.trackSelector.buildUponParameters()
                        .setMaxVideoBitrate(safeLimit)
                        .build()
                    adaptiveLimitBps = safeLimit
                    lastAdaptiveAtMs = now
                    message = "Adapting quality…"
                }
            }
            val shouldRecover = silentStall || (trace.health == PlaybackHealth.BUFFERING &&
                trace.bufferedAheadMs < 1_000L && decision.action == RecoveryAction.RESTART_PIPELINE)
            if (prefs.autoRetry && shouldRecover && retryCount < 3 && now - lastRecoveryAtMs >= 6_000L) {
                val resumeAt = player.currentPosition.coerceAtLeast(0L)
                retryCount++
                lastRecoveryAtMs = now
                trace = trace.copy(health = PlaybackHealth.RECOVERING, retryCount = retryCount)
                message = "Recovering $retryCount/3…"
                player.stop()
                player.setMediaItem(StreamPlayerFactory.mediaItem(item))
                player.prepare()
                if (item.kind != MediaKind.LIVE && resumeAt > 0L) player.seekTo(resumeAt)
                player.playWhenReady = true
                lastProgressAtMs = now
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
                    Player.STATE_BUFFERING -> "Buffering…"
                    Player.STATE_READY -> ""
                    Player.STATE_ENDED -> "Finished"
                    else -> "Connecting…"
                }
                trace = trace.copy(health = when (state) {
                    Player.STATE_BUFFERING -> {
                        if (hasPlayedOnce && rebufferStartedAtMs == 0L) {
                            rebufferStartedAtMs = System.currentTimeMillis()
                            trace = trace.copy(rebufferCount = trace.rebufferCount + 1)
                        }
                        PlaybackHealth.BUFFERING
                    }
                    Player.STATE_READY -> {
                        if (rebufferStartedAtMs > 0L) {
                            val elapsed = (System.currentTimeMillis() - rebufferStartedAtMs).coerceAtLeast(0L)
                            trace = trace.copy(
                                totalRebufferMs = trace.totalRebufferMs + elapsed,
                                maxRebufferMs = maxOf(trace.maxRebufferMs, elapsed),
                                lastRebufferMs = elapsed
                            )
                            rebufferStartedAtMs = 0L
                        }
                        if (player.isPlaying) PlaybackHealth.PLAYING else PlaybackHealth.READY
                    }
                    Player.STATE_ENDED -> PlaybackHealth.ENDED
                    else -> PlaybackHealth.CONNECTING
                })
                isPlaying = player.isPlaying
            }

            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) {
                    hasPlayedOnce = true
                    trace = trace.copy(health = PlaybackHealth.PLAYING)
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
                    trace = trace.copy(
                        videoWidth = video.width.coerceAtLeast(0),
                        videoHeight = video.height.coerceAtLeast(0),
                        videoBitrate = video.bitrate.coerceAtLeast(0),
                        videoMimeType = video.sampleMimeType,
                        videoCodecs = video.codecs
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Stream unavailable"
                trace = trace.copy(health = PlaybackHealth.ERROR, lastError = error.errorCodeName)
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
        val analytics = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                trace = trace.copy(droppedFrames = trace.droppedFrames + droppedFrames)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                trace = trace.copy(bandwidthEstimateBps = bitrateEstimate.coerceAtLeast(0L))
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                trace = trace.copy(
                    completedLoads = trace.completedLoads + 1,
                    lastLoadBytes = loadEventInfo.bytesLoaded.coerceAtLeast(0L),
                    lastLoadDurationMs = loadEventInfo.loadDurationMs.coerceAtLeast(0L),
                    lastLoadError = null
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                if (!wasCanceled) {
                    trace = trace.copy(
                        loadErrorCount = trace.loadErrorCount + 1,
                        lastLoadError = error.javaClass.simpleName
                    )
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            if (item.kind != MediaKind.LIVE) {
                val d = player.duration.coerceAtLeast(0L)
                val p = player.currentPosition.coerceAtLeast(0L)
                store.save(PlaybackRecord(item.id, item.name, p, d))
            }
            audioEffects.value?.release()
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
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

        TextButton(
            onClick = { showTrace = !showTrace },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
        ) { Text("360", color = Color(0xFF67D6FF), fontWeight = FontWeight.Bold) }

        if (showTrace) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 54.dp, end = 10.dp).widthIn(min = 210.dp, max = 300.dp),
                color = Color.Black.copy(alpha = .82f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("360 Playback Trace", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(trace.summary, color = Color(0xFF67D6FF), fontSize = 12.sp)
                    if (trace.sourceHost.isNotBlank()) Text("Source: " + trace.sourceHost, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Stage: " + trace.stage.name, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Source health: " + trace.sourceHealth.name, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Position: " + (trace.positionMs / 1000) + "s", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Buffered ahead: " + String.format("%.1f", trace.bufferedAheadMs / 1000f) + "s", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    if (trace.bandwidthEstimateBps > 0) Text("Bandwidth: " + String.format("%.2f", trace.bandwidthEstimateBps / 1_000_000f) + " Mbps", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    if (trace.videoHeight > 0) Text("Selected: " + trace.videoWidth + "x" + trace.videoHeight + (if (trace.videoBitrate > 0) " • " + String.format("%.2f", trace.videoBitrate / 1_000_000f) + " Mbps" else ""), color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    trace.videoMimeType?.let { Text("MIME: " + it, color = Color.White.copy(alpha=.8f), fontSize = 11.sp) }
                    trace.videoCodecs?.let { Text("Codec: " + it, color = Color.White.copy(alpha=.8f), fontSize = 11.sp) }
                    if (trace.completedLoads > 0) Text("Loads: " + trace.completedLoads + " • last " + trace.lastLoadBytes / 1024 + " KB / " + trace.lastLoadDurationMs + " ms", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Rebuffers: " + trace.rebufferCount + " • load errors: " + trace.loadErrorCount, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Rebuffer time: " + (trace.totalRebufferMs / 1000f) + "s • max " + (trace.maxRebufferMs / 1000f) + "s", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    val recoveryDecision = RecoveryBrain.decide(trace)
                    Text("Recovery: " + recoveryDecision.action.name, color = Color(0xFF78F1C7), fontSize = 11.sp)
                    if (adaptiveLimitBps > 0) Text("Adaptive ceiling: " + (adaptiveLimitBps / 1_000_000f) + " Mbps", color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    if (item.kind == MediaKind.SHORT_DRAMA) Text("Preload: " + MediaPreloadManager.state, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    trace.lastLoadError?.let { Text("Last load error: " + it, color = Color(0xFFFFDDB4), fontSize = 11.sp) }
                    Text("Dropped frames: " + trace.droppedFrames, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    Text("Recoveries: " + trace.retryCount, color = Color.White.copy(alpha=.8f), fontSize = 11.sp)
                    trace.lastError?.let { Text("Error: " + it, color = Color(0xFFFFB4AB), fontSize = 11.sp) }
                }
            }
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
