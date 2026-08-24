package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.engine.EnvironmentSummaryEngine
import com.example.domain.engine.FindObjectEngine
import com.example.domain.engine.MultiFrameTracker
import com.example.domain.engine.NotificationPriorityEngine
import com.example.domain.engine.ParsedVoiceResult
import com.example.domain.engine.SafePathGuidanceEngine
import com.example.domain.engine.VoiceCommand
import com.example.domain.engine.VoiceCommandParser
import com.example.domain.model.DangerLevel
import com.example.domain.model.GuidanceAlert
import com.example.domain.model.GuidanceState
import com.example.domain.model.PerformanceTelemetry
import com.example.domain.model.RawVisionDetection
import com.example.domain.model.SpatialZone
import com.example.domain.model.TrackedDetection
import com.example.hardware.AudioHapticFeedbackManager
import com.example.hardware.VoiceCommandManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val multiFrameTracker = MultiFrameTracker()
    private val priorityEngine = NotificationPriorityEngine()
    private val pathGuidanceEngine = SafePathGuidanceEngine()
    private val audioHapticManager = AudioHapticFeedbackManager(application.applicationContext)

    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    private val voiceCommandManager by lazy {
        VoiceCommandManager(
            context = application.applicationContext,
            onCommandRecognized = { parsedResult, transcript ->
                handleVoiceCommand(parsedResult, transcript)
            },
            onError = { errorMessage ->
                handleVoiceError(errorMessage)
            },
            onListeningStateChanged = { listening ->
                _guidanceState.update { it.copy(isListeningForVoiceCommand = listening) }
            }
        )
    }

    fun toggleGuidance() {
        val nextActiveState = !_guidanceState.value.isGuidanceActive
        if (nextActiveState) {
            multiFrameTracker.reset()
            priorityEngine.reset()
            pathGuidanceEngine.reset()
            _guidanceState.update {
                it.copy(
                    isGuidanceActive = true,
                    currentDangerLevel = DangerLevel.INFORMATION
                )
            }
            if (!_guidanceState.value.isAudioMuted) {
                audioHapticManager.speakText("Guidance active.", interrupt = true)
            }
        } else {
            multiFrameTracker.reset()
            priorityEngine.reset()
            pathGuidanceEngine.reset()
            audioHapticManager.silenceAll()
            if (!_guidanceState.value.isAudioMuted) {
                audioHapticManager.speakText("Guidance paused.", interrupt = true)
            }
            _guidanceState.update {
                it.copy(
                    isGuidanceActive = false,
                    trackedObjects = emptyList(),
                    currentDangerLevel = DangerLevel.INFORMATION
                )
            }
        }
    }

    fun toggleMute() {
        val nextMute = !_guidanceState.value.isAudioMuted
        _guidanceState.update { it.copy(isAudioMuted = nextMute) }
        if (nextMute) {
            audioHapticManager.silenceAll()
        } else {
            audioHapticManager.speakText("Audio unmuted.", interrupt = true)
        }
    }

    fun emergencyStopOrSilence() {
        audioHapticManager.silenceAll()
        if (_guidanceState.value.isGuidanceActive) {
            _guidanceState.update { it.copy(isAudioMuted = true) }
        }
    }

    fun startVoiceListening() {
        if (!_guidanceState.value.isAudioMuted) {
            audioHapticManager.speakText("Listening.", interrupt = false)
        }
        voiceCommandManager.startListening()
    }

    fun processVoiceTranscript(transcript: String?) {
        _guidanceState.update { it.copy(lastVoiceTranscript = transcript) }
        val parsedResult = VoiceCommandParser.parseCommand(transcript)
        if (parsedResult.command == VoiceCommand.UNKNOWN) {
            handleVoiceError("I didn't understand.")
        } else {
            handleVoiceCommand(parsedResult, transcript.orEmpty())
        }
    }

    private fun handleVoiceCommand(parsedResult: ParsedVoiceResult, transcript: String) {
        _guidanceState.update { it.copy(lastVoiceTranscript = transcript, isListeningForVoiceCommand = false) }
        when (parsedResult.command) {
            VoiceCommand.DESCRIBE_SURROUNDINGS -> {
                describeSurroundings()
            }
            VoiceCommand.FIND_OBJECT -> {
                val target = parsedResult.targetObject ?: "object"
                findObject(target)
            }
            VoiceCommand.SUGGEST_PATH -> {
                suggestPath()
            }
            VoiceCommand.STOP_GUIDANCE -> {
                if (_guidanceState.value.isGuidanceActive) {
                    toggleGuidance()
                }
            }
            VoiceCommand.START_GUIDANCE -> {
                if (!_guidanceState.value.isGuidanceActive) {
                    toggleGuidance()
                }
            }
            VoiceCommand.MUTE_AUDIO -> {
                if (!_guidanceState.value.isAudioMuted) {
                    toggleMute()
                }
            }
            VoiceCommand.UNMUTE_AUDIO -> {
                if (_guidanceState.value.isAudioMuted) {
                    toggleMute()
                }
            }
            VoiceCommand.UNKNOWN -> {
                handleVoiceError("I didn't understand.")
            }
        }
    }

    private fun handleVoiceError(errorMessage: String) {
        _guidanceState.update {
            it.copy(
                isListeningForVoiceCommand = false,
                sceneDescriptionSummary = errorMessage
            )
        }
        if (!_guidanceState.value.isAudioMuted) {
            audioHapticManager.speakText(errorMessage, interrupt = false)
        }
    }

    fun processFrameDetections(rawDetections: List<RawVisionDetection>, telemetry: PerformanceTelemetry) {
        viewModelScope.launch {
            val trackedObjects = multiFrameTracker.update(rawDetections)

            if (!_guidanceState.value.isGuidanceActive) {
                _guidanceState.update {
                    it.copy(
                        trackedObjects = trackedObjects,
                        telemetry = telemetry
                    )
                }
                return@launch
            }

            // Determine highest active danger level across all tracked objects
            val highestDangerLevel = trackedObjects
                .maxByOrNull { it.dangerScore }
                ?.dangerLevel ?: DangerLevel.INFORMATION

            // Evaluate next guidance alert according to priority queue & cooldowns
            val alert = priorityEngine.evaluateNextAlert(trackedObjects)

            if (alert != null) {
                audioHapticManager.dispatchGuidanceAlert(alert, _guidanceState.value.isAudioMuted)
                _guidanceState.update {
                    it.copy(
                        latestAlert = alert,
                        currentDangerLevel = highestDangerLevel,
                        trackedObjects = trackedObjects,
                        telemetry = telemetry
                    )
                }
            } else {
                _guidanceState.update {
                    it.copy(
                        currentDangerLevel = highestDangerLevel,
                        trackedObjects = trackedObjects,
                        telemetry = telemetry
                    )
                }
            }
        }
    }

    fun describeSurroundings() {
        viewModelScope.launch {
            _guidanceState.update { it.copy(isDescribingScene = true) }
            val currentObjects = _guidanceState.value.trackedObjects

            val summary = EnvironmentSummaryEngine.generateSummary(currentObjects)

            _guidanceState.update {
                it.copy(
                    sceneDescriptionSummary = summary,
                    isDescribingScene = false
                )
            }

            if (!_guidanceState.value.isAudioMuted) {
                audioHapticManager.speakText(summary, interrupt = false)
            }
        }
    }

    fun findObject(targetQuery: String) {
        viewModelScope.launch {
            _guidanceState.update { it.copy(isDescribingScene = true) }
            val currentObjects = _guidanceState.value.trackedObjects

            val response = FindObjectEngine.executeFind(targetQuery, currentObjects)

            _guidanceState.update {
                it.copy(
                    sceneDescriptionSummary = response,
                    isDescribingScene = false
                )
            }

            if (!_guidanceState.value.isAudioMuted) {
                audioHapticManager.speakText(response, interrupt = false)
            }
        }
    }

    fun suggestPath() {
        viewModelScope.launch {
            _guidanceState.update { it.copy(isDescribingScene = true) }
            val currentObjects = _guidanceState.value.trackedObjects
            val telemetry = _guidanceState.value.telemetry

            val pathResult = pathGuidanceEngine.evaluatePath(currentObjects, telemetry)

            _guidanceState.update {
                it.copy(
                    sceneDescriptionSummary = pathResult.spokenText,
                    isDescribingScene = false
                )
            }

            if (!_guidanceState.value.isAudioMuted) {
                audioHapticManager.speakText(pathResult.spokenText, interrupt = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceCommandManager.destroy()
        audioHapticManager.release()
    }
}
