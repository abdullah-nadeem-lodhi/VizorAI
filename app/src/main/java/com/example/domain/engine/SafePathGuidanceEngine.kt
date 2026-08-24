package com.example.domain.engine

import com.example.domain.model.DangerLevel
import com.example.domain.model.PerformanceTelemetry
import com.example.domain.model.SpatialZone
import com.example.domain.model.TrackedDetection

/**
 * Conservative Safe Path Guidance Engine (Iteration 9 - Challenge Bonus Feature).
 *
 * Evaluates the visible forward region (LEFT, CENTER, RIGHT) around detected obstacles
 * and provides conservative directional guidance.
 *
 * CRITICAL SAFETY RULES:
 * - NEVER claims that a direction is safe or clear to walk.
 * - FORBIDDEN: "The path is safe", "It is safe to walk", "Clear path", "Go right, it's safe", etc.
 * - PREFERRED: "Obstacle ahead. Move slightly right.", "Path blocked ahead. Right side appears less obstructed.",
 *   "Path blocked ahead. Left side appears less obstructed.", "Path appears obstructed. I can't determine a preferred direction."
 * - Treats rapid camera movement or insufficient detections conservatively as "Limited visibility. I can't determine a preferred direction."
 * - Level 3 hazards ALWAYS override path guidance with immediate speech interruption.
 */
enum class RecommendedDirection {
    MOVE_LEFT,
    MOVE_RIGHT,
    BLOCKED_NO_DIRECTION,
    UNOBSTRUCTED_AHEAD,
    LIMITED_VISIBILITY
}

data class SafePathResult(
    val spokenText: String,
    val shortDisplay: String,
    val recommendedDirection: RecommendedDirection,
    val leftObstructionScore: Float = 0f,
    val centerObstructionScore: Float = 0f,
    val rightObstructionScore: Float = 0f,
    val timestamp: Long = 0L
)

class SafePathGuidanceEngine {

    private var lastGuidanceTimestamp = -100000L
    private var lastRecommendedDirection: RecommendedDirection? = null
    private var lastSpokenText: String = ""

    // Cooldown between repeated identical path guidance recommendations (3000ms)
    private val guidanceCooldownMs = 3000L

    fun evaluatePath(
        trackedObjects: List<TrackedDetection>,
        telemetry: PerformanceTelemetry? = null,
        isRapidCameraMotion: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SafePathResult {
        // 1. Rapid camera motion or extreme frame jitter -> conservative fallback
        if (isRapidCameraMotion || (telemetry != null && telemetry.cameraFps < 4 && trackedObjects.isEmpty())) {
            return sanitizeAndWrap(
                spoken = "Limited visibility. I can't determine a preferred direction.",
                display = "Limited visibility",
                direction = RecommendedDirection.LIMITED_VISIBILITY,
                timestamp = currentTimeMs
            )
        }

        // 2. Filter valid tracked detections (require at least 2 persistence frames and confidence >= 0.35)
        val validObjects = trackedObjects.filter { it.persistenceFrames >= 2 && it.confidence >= 0.35f }

        // If no confirmed detections or only unconfirmed/low-confidence noise
        if (validObjects.isEmpty()) {
            val hasLowConfidenceNoise = trackedObjects.any { it.confidence in 0.20f..0.34f || it.persistenceFrames < 2 }
            return if (hasLowConfidenceNoise || trackedObjects.isEmpty()) {
                sanitizeAndWrap(
                    spoken = "Limited visibility. I can't determine a preferred direction.",
                    display = "Limited visibility",
                    direction = RecommendedDirection.LIMITED_VISIBILITY,
                    timestamp = currentTimeMs
                )
            } else {
                sanitizeAndWrap(
                    spoken = "Path ahead appears unobstructed in view.",
                    display = "Path appears open ahead",
                    direction = RecommendedDirection.UNOBSTRUCTED_AHEAD,
                    timestamp = currentTimeMs
                )
            }
        }

        // 3. Compute region obstruction scores for LEFT, CENTER, and RIGHT
        var leftScore = 0.0f
        var centerScore = 0.0f
        var rightScore = 0.0f

        var centerObstacleCount = 0
        var leftObstacleCount = 0
        var rightObstacleCount = 0

        for (obj in validObjects) {
            val score = calculateObstacleImpact(obj)
            val cx = obj.rect.centerX

            when {
                // Center corridor: 0.33 .. 0.67 or direct walking path
                obj.isDirectPath || (cx in 0.33f..0.67f) || obj.spatialZone == SpatialZone.CENTER -> {
                    centerScore += score
                    centerObstacleCount++
                    // If object spans widely into left or right, add proportional overflow
                    if (obj.rect.left < 0.33f) leftScore += score * 0.3f
                    if (obj.rect.right > 0.67f) rightScore += score * 0.3f
                }
                // Left corridor
                cx < 0.33f || obj.spatialZone == SpatialZone.LEFT || obj.spatialZone == SpatialZone.SLIGHT_LEFT -> {
                    leftScore += score
                    leftObstacleCount++
                }
                // Right corridor
                else -> {
                    rightScore += score
                    rightObstacleCount++
                }
            }
        }

        val centerBlocked = centerScore >= 5.0f || centerObstacleCount > 0

        // If Center is NOT blocked
        if (!centerBlocked) {
            return sanitizeAndWrap(
                spoken = "Path ahead appears unobstructed in view.",
                display = "Path open ahead",
                direction = RecommendedDirection.UNOBSTRUCTED_AHEAD,
                leftScore = leftScore,
                centerScore = centerScore,
                rightScore = rightScore,
                timestamp = currentTimeMs
            )
        }

        // 4. Center IS blocked: Compare Left vs Right conservatively
        val leftSignificant = leftScore >= 18.0f || leftObstacleCount >= 2
        val rightSignificant = rightScore >= 18.0f || rightObstacleCount >= 2

        // Both sides contain significant obstacles
        if (leftSignificant && rightSignificant) {
            return sanitizeAndWrap(
                spoken = "Path appears obstructed. I can't determine a preferred direction.",
                display = "Path heavily obstructed",
                direction = RecommendedDirection.BLOCKED_NO_DIRECTION,
                leftScore = leftScore,
                centerScore = centerScore,
                rightScore = rightScore,
                timestamp = currentTimeMs
            )
        }

        // Right is clearly less obstructed
        val isRightPreferred = (leftObstacleCount > 0 && rightObstacleCount == 0) ||
                (leftScore > rightScore + 2.0f && !rightSignificant)

        // Left is clearly less obstructed
        val isLeftPreferred = (rightObstacleCount > 0 && leftObstacleCount == 0) ||
                (rightScore > leftScore + 2.0f && !leftSignificant)

        return when {
            isRightPreferred && !isLeftPreferred -> {
                val spoken = if (leftScore > 20.0f) {
                    "Path blocked ahead. Move slightly right."
                } else {
                    "Obstacle ahead. Right side appears less obstructed."
                }
                sanitizeAndWrap(
                    spoken = spoken,
                    display = "Move slightly right",
                    direction = RecommendedDirection.MOVE_RIGHT,
                    leftScore = leftScore,
                    centerScore = centerScore,
                    rightScore = rightScore,
                    timestamp = currentTimeMs
                )
            }
            isLeftPreferred && !isRightPreferred -> {
                val spoken = if (rightScore > 20.0f) {
                    "Path blocked ahead. Move slightly left."
                } else {
                    "Obstacle ahead. Left side appears less obstructed."
                }
                sanitizeAndWrap(
                    spoken = spoken,
                    display = "Move slightly left",
                    direction = RecommendedDirection.MOVE_LEFT,
                    leftScore = leftScore,
                    centerScore = centerScore,
                    rightScore = rightScore,
                    timestamp = currentTimeMs
                )
            }
            else -> {
                sanitizeAndWrap(
                    spoken = "Path blocked ahead. I can't determine a preferred direction.",
                    display = "Obstacle ahead (Ambiguous)",
                    direction = RecommendedDirection.BLOCKED_NO_DIRECTION,
                    leftScore = leftScore,
                    centerScore = centerScore,
                    rightScore = rightScore,
                    timestamp = currentTimeMs
                )
            }
        }
    }

    /**
     * Checks if a new recommendation should be announced based on cooldown and meaningful state changes.
     */
    fun shouldAnnounce(newResult: SafePathResult, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val elapsed = currentTimeMs - lastGuidanceTimestamp
        val directionChanged = newResult.recommendedDirection != lastRecommendedDirection
        val textChanged = newResult.spokenText != lastSpokenText

        if (directionChanged || textChanged) {
            lastGuidanceTimestamp = currentTimeMs
            lastRecommendedDirection = newResult.recommendedDirection
            lastSpokenText = newResult.spokenText
            return true
        }

        if (elapsed >= guidanceCooldownMs) {
            lastGuidanceTimestamp = currentTimeMs
            return true
        }

        return false
    }

    fun reset() {
        lastGuidanceTimestamp = -100000L
        lastRecommendedDirection = null
        lastSpokenText = ""
    }

    private fun calculateObstacleImpact(obj: TrackedDetection): Float {
        val proximityMultiplier = (4.0f / obj.distanceMeters.coerceAtLeast(0.6f)).coerceIn(1.0f, 6.0f)
        val area = (obj.rect.width * obj.rect.height * 10.0f).coerceIn(0.8f, 5.0f)
        val pathMultiplier = if (obj.isDirectPath) 2.5f else 1.0f
        val dangerMultiplier = when (obj.dangerLevel) {
            DangerLevel.IMMEDIATE_HAZARD -> 4.0f
            DangerLevel.CAUTION -> 2.0f
            DangerLevel.INFORMATION -> 1.0f
        }
        val confidenceFactor = obj.confidence.coerceIn(0.4f, 1.0f)
        val persistenceFactor = (obj.persistenceFrames.coerceAtMost(5) / 5.0f).coerceAtLeast(0.5f)

        return (proximityMultiplier * area * pathMultiplier * dangerMultiplier * confidenceFactor * persistenceFactor * 2.0f)
            .coerceAtLeast(3.0f)
    }

    private fun sanitizeAndWrap(
        spoken: String,
        display: String,
        direction: RecommendedDirection,
        leftScore: Float = 0f,
        centerScore: Float = 0f,
        rightScore: Float = 0f,
        timestamp: Long = 0L
    ): SafePathResult {
        // Enforce strict safety constraints: strip any forbidden safety assurances
        val cleanSpoken = spoken
            .replace(Regex("(?i)\\bthe path is safe\\b"), "")
            .replace(Regex("(?i)\\bit is safe to walk\\b"), "")
            .replace(Regex("(?i)\\bclear path\\b"), "path appears open ahead")
            .replace(Regex("(?i)\\bgo right, it's safe\\b"), "move slightly right")
            .replace(Regex("(?i)\\bgo left, it's safe\\b"), "move slightly left")
            .replace(Regex("(?i)\\bsafe\\b"), "monitored")
            .trim()

        return SafePathResult(
            spokenText = cleanSpoken,
            shortDisplay = display,
            recommendedDirection = direction,
            leftObstructionScore = leftScore,
            centerObstructionScore = centerScore,
            rightObstructionScore = rightScore,
            timestamp = timestamp
        )
    }
}
