package com.novastream.tv

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer

enum class AudioPreset(val label: String) {
    NORMAL("Normal (Stereo)"),
    BASS_BOOST("Bass Boost"),
    SURROUND("Virtual Surround"),
    LOUD("Loudness Boost")
}

// Wraps Android's built-in system audio effects (not a real Dolby Atmos/branded
// DSP chain - those require licensed decoders/hardware passthrough the OS does
// not expose to apps). Bound to the ExoPlayer's own audio session so the effect
// applies to exactly what's currently playing.
class AudioEffectsController(audioSessionId: Int) {
    private val bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
    private val virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
    private val loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()

    fun apply(preset: AudioPreset) {
        runCatching { bassBoost?.enabled = false }
        runCatching { virtualizer?.enabled = false }
        runCatching { loudnessEnhancer?.setTargetGain(0) }
        runCatching { loudnessEnhancer?.enabled = false }
        when (preset) {
            AudioPreset.NORMAL -> Unit
            AudioPreset.BASS_BOOST -> runCatching {
                bassBoost?.setStrength(750)
                bassBoost?.enabled = true
            }
            AudioPreset.SURROUND -> runCatching {
                virtualizer?.setStrength(850)
                virtualizer?.enabled = true
            }
            AudioPreset.LOUD -> runCatching {
                loudnessEnhancer?.setTargetGain(900)
                loudnessEnhancer?.enabled = true
            }
        }
    }

    fun release() {
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
    }
}
