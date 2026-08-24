package com.example.domain.engine

import com.example.domain.model.DangerLevel
import com.example.domain.model.ObjectCategory

/**
 * Deterministic Multi-Signal Danger Scoring and Escalation Engine.
 *
 * Evaluates:
 * 1. Object hazard category (inherent kinetic risk)
 * 2. Conservative estimated distance
 * 3. Walking-path corridor overlap
 * 4. Approach velocity (closing speed vs receding)
 * 5. Temporal persistence and detection confidence
 *
 * Implements hysteresis thresholds to prevent flickering between danger levels.
 */
object DangerEngine {

    // Thresholds for initial escalation
    const val ESCALATE_TO_HAZARD_SCORE = 68.0f
    const val ESCALATE_TO_CAUTION_SCORE = 38.0f

    // Lower thresholds for de-escalation (Hysteresis gap)
    const val DEESCALATE_FROM_HAZARD_SCORE = 58.0f
    const val DEESCALATE_FROM_CAUTION_SCORE = 30.0f

    fun computeDanger(
        category: ObjectCategory,
        distanceMeters: Float,
        isDirectPath: Boolean,
        approachVelocityMps: Float,
        persistenceFrames: Int,
        confidence: Float,
        previousLevel: DangerLevel? = null
    ): Pair<Float, DangerLevel> {
        var score = 0.0f

        // 1. Object Category Inherent Risk Weight
        score += when (category) {
            ObjectCategory.VEHICLE, ObjectCategory.MOTORCYCLE -> 32.0f
            ObjectCategory.BICYCLE -> 28.0f
            ObjectCategory.STAIRS, ObjectCategory.CURB -> 30.0f
            ObjectCategory.POLE, ObjectCategory.WALL, ObjectCategory.OBSTACLE -> 22.0f
            ObjectCategory.PERSON -> 18.0f
            ObjectCategory.CHAIR, ObjectCategory.TABLE -> 14.0f
            ObjectCategory.DOOR -> 10.0f
        }

        // 2. Distance Proximity Scoring
        score += when {
            distanceMeters < 1.1f -> 44.0f
            distanceMeters < 2.0f -> 30.0f
            distanceMeters < 3.2f -> 16.0f
            distanceMeters < 4.5f -> 6.0f
            else -> 0.0f
        }

        // 3. Direct Walking Path Overlap
        if (isDirectPath) {
            score += 24.0f
        } else {
            // Objects outside direct walking path get distance score attenuation
            score -= 6.0f
        }

        // 4. Movement Dynamics (Approach vs Receding)
        if (approachVelocityMps > 1.2f) {
            // Fast closing speed (e.g. approaching vehicle, running person)
            score += 26.0f
        } else if (approachVelocityMps > 0.4f) {
            // Moderate closing speed
            score += 14.0f
        } else if (approachVelocityMps < -0.4f) {
            // Moving away (receding) -> actively reduce urgency
            score -= 22.0f
        }

        // 5. Confidence & Temporal Stability Modulation
        if (confidence < 0.50f || persistenceFrames < 2) {
            score *= 0.65f
        }

        // 6. Safety Rule: Distance alone cannot trigger Level 3 (Immediate Hazard)
        // Level 3 strictly requires:
        // (a) Direct walking path overlap, OR
        // (b) Fast closing approach velocity (> 0.8 m/s), OR
        // (c) High-risk mobile vehicle/motorcycle
        val isEligibleForImmediateHazard = isDirectPath ||
                approachVelocityMps > 0.8f ||
                category == ObjectCategory.VEHICLE ||
                category == ObjectCategory.MOTORCYCLE

        var clampedScore = score.coerceIn(0.0f, 100.0f)
        if (!isEligibleForImmediateHazard && clampedScore >= ESCALATE_TO_HAZARD_SCORE) {
            clampedScore = ESCALATE_TO_HAZARD_SCORE - 2.0f // Cap at high Caution
        }

        // 7. Classification with Hysteresis
        val level = when (previousLevel) {
            DangerLevel.IMMEDIATE_HAZARD -> {
                if (clampedScore < DEESCALATE_FROM_HAZARD_SCORE) {
                    if (clampedScore >= ESCALATE_TO_CAUTION_SCORE) DangerLevel.CAUTION else DangerLevel.INFORMATION
                } else {
                    DangerLevel.IMMEDIATE_HAZARD
                }
            }
            DangerLevel.CAUTION -> {
                if (clampedScore >= ESCALATE_TO_HAZARD_SCORE && isEligibleForImmediateHazard) {
                    DangerLevel.IMMEDIATE_HAZARD
                } else if (clampedScore < DEESCALATE_FROM_CAUTION_SCORE) {
                    DangerLevel.INFORMATION
                } else {
                    DangerLevel.CAUTION
                }
            }
            else -> {
                if (clampedScore >= ESCALATE_TO_HAZARD_SCORE && isEligibleForImmediateHazard) {
                    DangerLevel.IMMEDIATE_HAZARD
                } else if (clampedScore >= ESCALATE_TO_CAUTION_SCORE) {
                    DangerLevel.CAUTION
                } else {
                    DangerLevel.INFORMATION
                }
            }
        }

        return Pair(clampedScore, level)
    }
}
