package com.novastream.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

sealed class GestureFeedback {
    data class Brightness(val percent: Int) : GestureFeedback()
    data class Volume(val percent: Int) : GestureFeedback()
    data class Seek(val seconds: Int, val forward: Boolean) : GestureFeedback()
}

class PlayerGestureController(
    private val activity: Activity,
    private val target: View,
    private val brightnessSensitivity: Float = 0.30f,
    private val volumeSensitivity: Float = 0.60f,
    private val canSeek: () -> Boolean = { false },
    private val onSeek: (Long) -> Unit = {},
    private val onFeedback: (GestureFeedback?) -> Unit = {},
    private val onTap: () -> Unit = {}
) : View.OnTouchListener {
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var startX = 0f
    private var startY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var mode = 0 // 0 undecided, 1 vertical, 2 horizontal
    private var brightnessSide = false
    private var moved = false
    private var seekDeltaMs = 0L

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; moved = false; mode = 0; seekDeltaMs = 0L
                brightnessSide = startX < target.width / 2f
                val lp = activity.window.attributes
                startBrightness = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (target.height <= 0 || target.width <= 0) return true
                val dx = event.x - startX
                val dy = startY - event.y
                if (mode == 0 && (abs(dx) > 18f || abs(dy) > 18f)) {
                    moved = true
                    mode = if (abs(dx) > abs(dy) * 1.15f && canSeek()) 2 else 1
                }
                if (mode == 2) {
                    val fraction = (dx / target.width.toFloat()).coerceIn(-1f, 1f)
                    seekDeltaMs = (fraction * 90_000L).toLong()
                    val seconds = (abs(seekDeltaMs) / 1000L).toInt()
                    onFeedback(GestureFeedback.Seek(seconds, seekDeltaMs >= 0))
                } else if (mode == 1) {
                    val normalized = dy / target.height.toFloat()
                    if (brightnessSide) {
                        val value = GestureMath.brightness(startBrightness, normalized, brightnessSensitivity)
                        val lp: WindowManager.LayoutParams = activity.window.attributes
                        lp.screenBrightness = value; activity.window.attributes = lp
                        onFeedback(GestureFeedback.Brightness((value * 100).toInt()))
                    } else {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = GestureMath.volume(startVolume, max, normalized, volumeSensitivity)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        onFeedback(GestureFeedback.Volume(if (max > 0) value * 100 / max else 0))
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
                else if (mode == 2 && seekDeltaMs != 0L) onSeek(seekDeltaMs)
                onFeedback(null)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { onFeedback(null); return true }
        }
        return false
    }
}
