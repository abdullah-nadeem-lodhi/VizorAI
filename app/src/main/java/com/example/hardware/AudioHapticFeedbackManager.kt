package com.example.hardware

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.domain.model.DangerLevel
import com.example.domain.model.GuidanceAlert
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages synchronized Text-to-Speech audio and distinct accessible haptic feedback waveforms (Spec Section 10 & 11).
 */
class AudioHapticFeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "AudioHapticFeedback"
    private var tts: TextToSpeech? = null
    private val isTtsReady = AtomicBoolean(false)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(tag, "TTS English not supported, using default locale")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            isTtsReady.set(true)
            Log.i(tag, "TTS Engine Initialized Successfully")
        } else {
            Log.e(tag, "TTS Engine Initialization Failed: $status")
        }
    }

    fun dispatchGuidanceAlert(alert: GuidanceAlert, isAudioMuted: Boolean) {
        // Trigger distinct haptic feedback regardless of audio mute status
        triggerHapticPattern(alert.level)

        if (isAudioMuted) {
            return
        }

        if (isTtsReady.get() && tts != null) {
            // SURGICAL FIX: Prevent normal camera alerts from queuing up behind
            // or interrupting ongoing long-form speech (e.g. Find Object responses).
            if (!alert.requiresInterruption && tts?.isSpeaking == true) {
                return
            }

            val queueMode = if (alert.requiresInterruption) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            tts?.speak(alert.spokenText, queueMode, null, alert.id)
        }
    }

    fun speakText(text: String, interrupt: Boolean = true) {
        if (isTtsReady.get() && tts != null) {
            val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, queueMode, null, "custom_speech_${System.currentTimeMillis()}")
        }
    }

    fun silenceAll() {
        try {
            if (isTtsReady.get() && tts != null) {
                tts?.stop()
            }
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(tag, "Error silencing audio/haptics: ${e.message}")
        }
    }

    private fun triggerHapticPattern(level: DangerLevel) {
        try {
            if (vibrator == null || !vibrator!!.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (level) {
                    DangerLevel.INFORMATION -> {
                        // Light single tap
                        VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    DangerLevel.CAUTION -> {
                        // Double caution pulse
                        val timings = longArrayOf(0, 80, 70, 90)
                        val amplitudes = intArrayOf(0, 180, 0, 210)
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    }
                    DangerLevel.IMMEDIATE_HAZARD -> {
                        // Strong urgent hazard alarm waveform
                        val timings = longArrayOf(0, 200, 60, 200, 60, 250)
                        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    }
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (level) {
                    DangerLevel.INFORMATION -> vibrator?.vibrate(40L)
                    DangerLevel.CAUTION -> vibrator?.vibrate(longArrayOf(0, 80, 70, 90), -1)
                    DangerLevel.IMMEDIATE_HAZARD -> vibrator?.vibrate(longArrayOf(0, 200, 60, 200, 60, 250), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Haptic trigger failed: ${e.message}")
        }
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing feedback manager: ${e.message}")
        }
    }
}
