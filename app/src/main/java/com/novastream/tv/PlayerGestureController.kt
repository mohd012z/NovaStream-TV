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
}

class PlayerGestureController(
    private val activity: Activity,
    private val target: View,
    private val brightnessSensitivity: Float = 0.30f,
    private val volumeSensitivity: Float = 0.60f,
    private val onFeedback: (GestureFeedback?) -> Unit = {},
    private val onTap: () -> Unit = {}
) : View.OnTouchListener {

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var startX = 0f
    private var startY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var gestureBrightness = false
    private var moved = false

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                moved = false
                gestureBrightness = startX < target.width / 2f
                val lp = activity.window.attributes
                startBrightness = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (target.height <= 0) return true
                val dy = startY - event.y
                if (abs(dy) > 14f) moved = true
                val normalized = dy / target.height.toFloat()
                if (gestureBrightness) {
                    val newBrightness = GestureMath.brightness(startBrightness, normalized, brightnessSensitivity)
                    val lp: WindowManager.LayoutParams = activity.window.attributes
                    lp.screenBrightness = newBrightness
                    activity.window.attributes = lp
                    onFeedback(GestureFeedback.Brightness((newBrightness * 100).toInt()))
                } else {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val newVolume = GestureMath.volume(startVolume, maxVolume, normalized, volumeSensitivity)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    val percent = if (maxVolume > 0) newVolume * 100 / maxVolume else 0
                    onFeedback(GestureFeedback.Volume(percent))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
                onFeedback(null)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                onFeedback(null)
                return true
            }
        }
        return false
    }
}
