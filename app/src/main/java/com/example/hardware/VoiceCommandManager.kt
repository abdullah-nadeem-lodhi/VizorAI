package com.example.hardware

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.domain.engine.ParsedVoiceResult
import com.example.domain.engine.VoiceCommand
import com.example.domain.engine.VoiceCommandParser
import java.util.Locale

/**
 * Manages speech recognition for non-safety-critical voice commands (Iteration 8).
 *
 * Implements clean fallback to "I didn't understand." on any recognition failure or unknown command.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommandRecognized: (ParsedVoiceResult, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit = {}
) {
    private val tag = "VoiceCommandManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun isVoiceRecognitionSupported(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Throwable) {
            false
        }
    }

    fun startListening() {
        mainHandler.post {
            try {
                if (!isVoiceRecognitionSupported()) {
                    Log.w(tag, "Speech recognition not available on device")
                    onError("I didn't understand.")
                    return@post
                }

                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                isListening = true
                onListeningStateChanged(true)
                speechRecognizer?.startListening(intent)
            } catch (e: Throwable) {
                Log.e(tag, "Failed to start speech recognition: ${e.message}")
                isListening = false
                onListeningStateChanged(false)
                onError("I didn't understand.")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                onListeningStateChanged(false)
                speechRecognizer?.stopListening()
            } catch (e: Throwable) {
                Log.e(tag, "Error stopping speech recognition: ${e.message}")
            }
        }
    }

    fun processTranscriptDirectly(transcript: String?) {
        val parsed = VoiceCommandParser.parseCommand(transcript)
        if (parsed.command == VoiceCommand.UNKNOWN) {
            onError("I didn't understand.")
        } else {
            onCommandRecognized(parsed, transcript.orEmpty())
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            Log.w(tag, "Speech recognition error code: $error")
            isListening = false
            onListeningStateChanged(false)
            onError("I didn't understand.")
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStateChanged(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val bestMatch = matches?.firstOrNull()

            if (bestMatch.isNullOrBlank()) {
                onError("I didn't understand.")
                return
            }

            processTranscriptDirectly(bestMatch)
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Throwable) {
                Log.e(tag, "Error destroying speech recognizer: ${e.message}")
            }
        }
    }
}
