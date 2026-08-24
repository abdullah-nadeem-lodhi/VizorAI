package com.example.domain.engine

import com.example.domain.model.DistanceBucket
import com.example.domain.model.SpatialZone
import com.example.domain.model.TrackedDetection

/**
 * Deterministic Environment Scene Summarizer (Iteration 7).
 *
 * Generates concise, natural language descriptions of visible surroundings based on
 * real tracked objects and spatial geometry.
 *
 * STRICT SAFETY RULES:
 * - NEVER states "The area is safe" or "You can walk forward safely".
 * - Communicates uncertainty explicitly for low-confidence detections ("Possible ...").
 * - Returns "I couldn't identify enough of the surroundings." when no confirmed objects are detected.
 * - Concise output (top 3-4 most relevant objects) to prevent auditory overload.
 */
object EnvironmentSummaryEngine {

    fun generateSummary(trackedObjects: List<TrackedDetection>): String {
        // Filter out unconfirmed noise (require at least 2 persistence frames and confidence >= 0.35)
        val validObjects = trackedObjects.filter { it.persistenceFrames >= 2 && it.confidence >= 0.35f }

        if (validObjects.isEmpty()) {
            return "I couldn't identify enough of the surroundings."
        }

        // Sort by spatial priority: In-path / center first, then closer distance, then higher confidence
        val prioritized = validObjects.sortedWith(
            compareByDescending<TrackedDetection> { it.isDirectPath }
                .thenBy { it.distanceMeters }
                .thenByDescending { it.confidence }
        ).distinctBy { "${it.label}_${it.spatialZone}" } // Deduplicate identical labels in the same zone
        .take(3)

        val clauses = prioritized.map { obj ->
            formatObjectClause(obj)
        }

        val joinedText = when (clauses.size) {
            1 -> "There is ${clauses[0]}."
            2 -> "There is ${clauses[0]}, and ${clauses[1]}."
            else -> "There is ${clauses[0]}, ${clauses[1]}, and ${clauses[2]}."
        }

        return sanitizeSummary(joinedText)
    }

    private fun formatObjectClause(obj: TrackedDetection): String {
        val isLowConfidence = obj.confidence < 0.60f
        val prefix = if (isLowConfidence) "possible " else ""
        val article = if (isLowConfidence) "a " else getIndefiniteArticle(obj.label)
        val noun = obj.label.lowercase()

        val locationQualifier = when (obj.spatialZone) {
            SpatialZone.CENTER -> {
                when (obj.distanceBucket) {
                    DistanceBucket.VERY_CLOSE -> "close ahead"
                    DistanceBucket.NEARBY -> "ahead"
                    DistanceBucket.FAR -> "farther ahead"
                }
            }
            SpatialZone.SLIGHT_LEFT -> {
                when (obj.distanceBucket) {
                    DistanceBucket.VERY_CLOSE -> "close to your slight left"
                    DistanceBucket.NEARBY -> "slightly left"
                    DistanceBucket.FAR -> "farther to your slight left"
                }
            }
            SpatialZone.SLIGHT_RIGHT -> {
                when (obj.distanceBucket) {
                    DistanceBucket.VERY_CLOSE -> "close to your slight right"
                    DistanceBucket.NEARBY -> "slightly right"
                    DistanceBucket.FAR -> "farther to your slight right"
                }
            }
            SpatialZone.LEFT -> {
                when (obj.distanceBucket) {
                    DistanceBucket.VERY_CLOSE -> "close to your left"
                    DistanceBucket.NEARBY -> "to your left"
                    DistanceBucket.FAR -> "farther to your left"
                }
            }
            SpatialZone.RIGHT -> {
                when (obj.distanceBucket) {
                    DistanceBucket.VERY_CLOSE -> "close to your right"
                    DistanceBucket.NEARBY -> "to your right"
                    DistanceBucket.FAR -> "farther to your right"
                }
            }
        }

        return "$article$prefix$noun $locationQualifier"
    }

    private fun getIndefiniteArticle(word: String): String {
        val firstChar = word.trim().lowercase().firstOrNull() ?: return "a "
        return if (firstChar in listOf('a', 'e', 'i', 'o', 'u')) "an " else "a "
    }

    private fun sanitizeSummary(text: String): String {
        // Enforce strict safety constraints: strip any dangerous affirmations
        return text
            .replace(Regex("(?i)\\bsafe\\b"), "monitored")
            .replace(Regex("(?i)\\bclear to walk\\b"), "")
            .replace(Regex("(?i)\\bfree of obstacles\\b"), "")
    }
}
