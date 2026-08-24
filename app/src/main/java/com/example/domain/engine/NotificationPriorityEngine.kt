package com.example.domain.engine

import com.example.domain.model.DangerLevel
import com.example.domain.model.GuidanceAlert
import com.example.domain.model.ObjectCategory
import com.example.domain.model.SpatialZone
import com.example.domain.model.TrackedDetection
import java.util.UUID

/**
 * Smart Audio + Haptic Guidance Priority and Fatigue-Mitigation Engine (Iteration 6).
 *
 * Implements strict hierarchical prioritization:
 * 1. Immediate Hazards (Level 3) -> Interrupts speech immediately & triggers urgent haptic waveform
 * 2. Approaching Hazards (closing velocity > 0.4 m/s)
 * 3. Danger-level changes (state transitions)
 * 4. Objects in direct walking path
 * 5. Nearby useful objects
 *
 * Prevents warning fatigue through temporal deduplication, level-specific cooldowns,
 * and uncertainty-aware natural phrasing.
 */
class NotificationPriorityEngine {

    private var lastSpokenTimestamp = -100000L
    private var lastSpokenTrackId = -1
    private var lastSpokenLevel: DangerLevel? = null
    private var lastSpokenZone: SpatialZone? = null
    private var lastSpokenDistanceBucket: String = ""

    // Cooldown timings (ms) to prevent announcement flooding
    private val hazardCooldownMs = 1200L
    private val cautionCooldownMs = 2800L
    private val infoCooldownMs = 5000L

    fun evaluateNextAlert(
        trackedObjects: List<TrackedDetection>,
        currentTimeMs: Long = System.currentTimeMillis()
    ): GuidanceAlert? {
        if (trackedObjects.isEmpty()) {
            return null
        }

        // Filter out unconfirmed noise (requires at least 2 persistence frames and >= 0.35 confidence)
        val candidateObjects = trackedObjects.filter { it.persistenceFrames >= 2 && it.confidence >= 0.35f }
        if (candidateObjects.isEmpty()) {
            return null
        }

        // Hierarchical comparator matching exact safety hierarchy:
        // 1. Level 3 Immediate Hazards first
        // 2. High danger score
        // 3. Approaching velocity (closing speed)
        // 4. In direct walking path
        // 5. Closer distance
        // 6. Higher persistence
        val primaryTarget = candidateObjects.maxWithOrNull(
            Comparator<TrackedDetection> { a, b ->
                // Tier 1: Immediate Hazard rank
                val aIsHazard = a.dangerLevel == DangerLevel.IMMEDIATE_HAZARD
                val bIsHazard = b.dangerLevel == DangerLevel.IMMEDIATE_HAZARD
                if (aIsHazard != bIsHazard) {
                    return@Comparator if (aIsHazard) 1 else -1
                }

                // Tier 2: Overall danger score (combines category, path, approach, distance)
                val scoreDiff = a.dangerScore.compareTo(b.dangerScore)
                if (scoreDiff != 0) {
                    return@Comparator scoreDiff
                }

                // Tier 3: Approach velocity
                val velDiff = a.approachVelocityMps.compareTo(b.approachVelocityMps)
                if (velDiff != 0) {
                    return@Comparator velDiff
                }

                // Tier 4: Direct walking path
                if (a.isDirectPath != b.isDirectPath) {
                    return@Comparator if (a.isDirectPath) 1 else -1
                }

                // Tier 5: Distance (closer is higher priority, so reverse)
                val distDiff = b.distanceMeters.compareTo(a.distanceMeters)
                if (distDiff != 0) {
                    return@Comparator distDiff
                }

                // Tier 6: Persistence frames
                a.persistenceFrames.compareTo(b.persistenceFrames)
            }
        ) ?: return null

        val isImmediateHazard = primaryTarget.dangerLevel == DangerLevel.IMMEDIATE_HAZARD
        val cooldown = when (primaryTarget.dangerLevel) {
            DangerLevel.IMMEDIATE_HAZARD -> hazardCooldownMs
            DangerLevel.CAUTION -> cautionCooldownMs
            DangerLevel.INFORMATION -> infoCooldownMs
        }

        val elapsed = currentTimeMs - lastSpokenTimestamp
        val isSameTarget = primaryTarget.trackId == lastSpokenTrackId
        val isSameLevel = primaryTarget.dangerLevel == lastSpokenLevel
        val isSameZone = primaryTarget.spatialZone == lastSpokenZone
        val isSameDistance = primaryTarget.distanceBucket.displayName == lastSpokenDistanceBucket

        // State change detection: target change, level escalation/de-escalation, zone shift, distance shift
        val hasMeaningfulStateChange = !isSameTarget || !isSameLevel || !isSameZone || !isSameDistance

        // Repetition suppression within cooldown unless there's a meaningful state change (e.g. escalation, target shift, or zone/distance transition)
        if (!hasMeaningfulStateChange && elapsed < cooldown) {
            return null
        }

        // Pacing guard: prevent back-to-back rapid chatter
        if (elapsed < (cooldown / 2) && !isImmediateHazard && !hasMeaningfulStateChange) {
            return null
        }

        // Format short, clear, uncertainty-aware spoken text
        val (spokenText, shortText) = formatGuidanceSpeech(primaryTarget)

        // Update announcement state cache
        lastSpokenTimestamp = currentTimeMs
        lastSpokenTrackId = primaryTarget.trackId
        lastSpokenLevel = primaryTarget.dangerLevel
        lastSpokenZone = primaryTarget.spatialZone
        lastSpokenDistanceBucket = primaryTarget.distanceBucket.displayName

        return GuidanceAlert(
            id = UUID.randomUUID().toString(),
            level = primaryTarget.dangerLevel,
            spokenText = spokenText,
            shortDisplay = shortText,
            timestamp = currentTimeMs,
            requiresInterruption = isImmediateHazard
        )
    }

    fun reset() {
        lastSpokenTimestamp = -100000L
        lastSpokenTrackId = -1
        lastSpokenLevel = null
        lastSpokenZone = null
        lastSpokenDistanceBucket = ""
    }

    private fun formatGuidanceSpeech(target: TrackedDetection): Pair<String, String> {
        val labelName = target.label.replaceFirstChar { it.uppercase() }
        val zoneText = target.spatialZone.displayName
        val distanceInt = target.distanceMeters.toInt()
        val isLowConfidence = target.confidence < 0.60f
        val subjectPrefix = if (isLowConfidence) "Possible " else ""

        return when (target.dangerLevel) {
            DangerLevel.IMMEDIATE_HAZARD -> {
                if (target.approachVelocityMps > 1.0f && target.category.isMobileHazard) {
                    val speech = "Warning. ${target.label} approaching fast."
                    val display = "HAZARD: ${labelName} approaching!"
                    Pair(speech, display)
                } else if (target.isDirectPath) {
                    val speech = "STOP. $labelName directly ahead."
                    val display = "STOP: $labelName ahead (~${distanceInt}m)"
                    Pair(speech, display)
                } else {
                    val speech = "Warning. $labelName very close $zoneText."
                    val display = "HAZARD: $labelName $zoneText"
                    Pair(speech, display)
                }
            }
            DangerLevel.CAUTION -> {
                if (target.approachVelocityMps > 0.6f) {
                    val speech = "$subjectPrefix$labelName moving closer $zoneText."
                    val display = "$labelName moving closer ($zoneText)"
                    Pair(speech, display)
                } else if (target.isDirectPath) {
                    val speech = "$subjectPrefix$labelName close in walking path."
                    val display = "$labelName in path (~${distanceInt}m)"
                    Pair(speech, display)
                } else {
                    val speech = "$subjectPrefix$labelName close $zoneText."
                    val display = "$labelName close ($zoneText)"
                    Pair(speech, display)
                }
            }
            DangerLevel.INFORMATION -> {
                if (isLowConfidence) {
                    val speech = "Possible $labelName $zoneText."
                    val display = "Possible $labelName ($zoneText)"
                    Pair(speech, display)
                } else {
                    val distanceDescriptor = if (target.distanceMeters < 5.0f && distanceInt >= 1) {
                        "approximately $distanceInt meters $zoneText"
                    } else {
                        zoneText
                    }
                    val speech = "$labelName $distanceDescriptor."
                    val display = "$labelName $zoneText"
                    Pair(speech, display)
                }
            }
        }
    }
}
