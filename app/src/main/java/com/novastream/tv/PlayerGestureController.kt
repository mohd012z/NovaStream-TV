package com.novastream.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Smooth left/right swipe controller.
 * Left half: brightness. Right half: volume.
 * Uses total swipe distance from ACTION_DOWN to avoid one-volume-step-per-ACTION_MOVE.
 */
class PlayerGestureController(
    private val activity: Activity,
    private val target: View,
    private val brightnessSensitivity: Float = 0.30f,
    private val volumeSensitivity: Float = 0.60f
) : View.OnTouchListener {

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var startX = 0f
    private var startY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var gestureBrightness = false

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                gestureBrightness = startX < target.width / 2f
                val lp = activity.window.attributes
                startBrightness = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (target.height <= 0) return true
                val normalized = (startY - event.y) / target.height.toFloat()
                if (gestureBrightness) {
                    val newBrightness = (startBrightness + normalized * brightnessSensitivity)
                        .coerceIn(0.05f, 1f)
                    val lp: WindowManager.LayoutParams = activity.window.attributes
                    lp.screenBrightness = newBrightness
                    activity.window.attributes = lp
                } else {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val delta = (normalized * maxVolume * volumeSensitivity).roundToInt()
                    val newVolume = (startVolume + delta).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }
}
