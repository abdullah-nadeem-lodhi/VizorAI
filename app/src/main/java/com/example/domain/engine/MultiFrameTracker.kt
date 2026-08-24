package com.example.domain.engine

import com.example.domain.model.DangerLevel
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ObjectCategory
import com.example.domain.model.RawVisionDetection
import com.example.domain.model.TrackedDetection
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-frame temporal object tracking with velocity, persistence, and stateful hysteresis tracking.
 */
class MultiFrameTracker {

    private var nextTrackId = 1
    private val activeTracks = mutableListOf<InternalTrack>()

    private data class InternalTrack(
        val trackId: Int,
        var category: ObjectCategory,
        var label: String,
        var confidence: Float,
        var rect: NormalizedRect,
        var distanceMeters: Float,
        var approachVelocityMps: Float,
        var persistenceFrames: Int,
        var lastUpdated: Long,
        var previousDistance: Float,
        var previousTimestamp: Long,
        var previousDangerLevel: DangerLevel = DangerLevel.INFORMATION
    )

    fun update(rawDetections: List<RawVisionDetection>, currentTimeMs: Long = System.currentTimeMillis()): List<TrackedDetection> {
        val unmatchedTracks = activeTracks.toMutableList()
        val updatedTracks = mutableListOf<InternalTrack>()

        for (detection in rawDetections) {
            val estimatedDistance = SpatialDistanceEstimator.estimateDistanceMeters(detection.category, detection.rect)
            
            // Find best matching existing track based on IoU and centroid distance
            var bestMatch: InternalTrack? = null
            var bestScore = 0.0f

            for (track in unmatchedTracks) {
                if (track.category == detection.category) {
                    val iou = computeIoU(track.rect, detection.rect)
                    val centroidDist = computeCentroidDistance(track.rect, detection.rect)
                    val proximityScore = (iou * 0.7f) + ((1.0f - centroidDist.coerceIn(0f, 1f)) * 0.3f)

                    if (proximityScore > 0.25f && proximityScore > bestScore) {
                        bestScore = proximityScore
                        bestMatch = track
                    }
                }
            }

            if (bestMatch != null) {
                unmatchedTracks.remove(bestMatch)
                val dt = (currentTimeMs - bestMatch.previousTimestamp).coerceAtLeast(1) / 1000f
                val deltaDist = bestMatch.previousDistance - estimatedDistance // positive if approaching
                val rawVelocity = if (dt > 0.05f) (deltaDist / dt).coerceIn(-5.0f, 10.0f) else 0f
                
                // EMA smoothing for velocity and distance
                val smoothedVelocity = (bestMatch.approachVelocityMps * 0.6f) + (rawVelocity * 0.4f)
                val smoothedDistance = (bestMatch.distanceMeters * 0.7f) + (estimatedDistance * 0.3f)

                bestMatch.rect = detection.rect
                bestMatch.confidence = (bestMatch.confidence * 0.5f) + (detection.confidence * 0.5f)
                bestMatch.distanceMeters = smoothedDistance
                bestMatch.approachVelocityMps = smoothedVelocity
                bestMatch.persistenceFrames += 1
                bestMatch.previousDistance = estimatedDistance
                bestMatch.previousTimestamp = currentTimeMs
                bestMatch.lastUpdated = currentTimeMs
                updatedTracks.add(bestMatch)
            } else {
                // New track candidate
                val newTrack = InternalTrack(
                    trackId = nextTrackId++,
                    category = detection.category,
                    label = detection.label,
                    confidence = detection.confidence,
                    rect = detection.rect,
                    distanceMeters = estimatedDistance,
                    approachVelocityMps = 0f,
                    persistenceFrames = 1,
                    lastUpdated = currentTimeMs,
                    previousDistance = estimatedDistance,
                    previousTimestamp = currentTimeMs,
                    previousDangerLevel = DangerLevel.INFORMATION
                )
                updatedTracks.add(newTrack)
            }
        }

        // Retain tracks that weren't detected in this single frame if recent (< 800ms) with decayed persistence
        for (staleTrack in unmatchedTracks) {
            if (currentTimeMs - staleTrack.lastUpdated < 800) {
                staleTrack.confidence *= 0.85f
                updatedTracks.add(staleTrack)
            }
        }

        activeTracks.clear()
        activeTracks.addAll(updatedTracks)

        // Convert to domain TrackedDetection with Spatial & Danger evaluation
        return activeTracks.map { track ->
            val zone = SpatialDistanceEstimator.determineSpatialZone(track.rect)
            val clock = SpatialDistanceEstimator.determineClockDirection(track.rect)
            val bucket = SpatialDistanceEstimator.categorizeDistance(track.distanceMeters)
            val isDirectPath = SpatialDistanceEstimator.isDirectWalkingPath(track.rect)
            
            val (dangerScore, dangerLevel) = DangerEngine.computeDanger(
                category = track.category,
                distanceMeters = track.distanceMeters,
                isDirectPath = isDirectPath,
                approachVelocityMps = track.approachVelocityMps,
                persistenceFrames = track.persistenceFrames,
                confidence = track.confidence,
                previousLevel = track.previousDangerLevel
            )

            track.previousDangerLevel = dangerLevel

            TrackedDetection(
                trackId = track.trackId,
                category = track.category,
                label = track.label,
                confidence = track.confidence,
                rect = track.rect,
                distanceMeters = track.distanceMeters,
                distanceBucket = bucket,
                spatialZone = zone,
                clockDirection = clock,
                approachVelocityMps = track.approachVelocityMps,
                isDirectPath = isDirectPath,
                persistenceFrames = track.persistenceFrames,
                dangerScore = dangerScore,
                dangerLevel = dangerLevel,
                lastUpdatedTimestamp = track.lastUpdated
            )
        }
    }

    fun reset() {
        activeTracks.clear()
        nextTrackId = 1
    }

    private fun computeIoU(a: NormalizedRect, b: NormalizedRect): Float {
        val xA = max(a.left, b.left)
        val yA = max(a.top, b.top)
        val xB = min(a.right, b.right)
        val yB = min(a.bottom, b.bottom)

        val interWidth = max(0f, xB - xA)
        val interHeight = max(0f, yB - yA)
        val interArea = interWidth * interHeight

        val unionArea = a.area + b.area - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun computeCentroidDistance(a: NormalizedRect, b: NormalizedRect): Float {
        return hypot(a.centerX - b.centerX, a.centerY - b.centerY)
    }
}
