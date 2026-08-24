package com.example.domain.engine

import com.example.domain.model.DistanceBucket
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ObjectCategory
import com.example.domain.model.SpatialZone

/**
 * Deterministic spatial direction, walking path overlap, and conservative distance estimator.
 *
 * Employs conservative bounding-box geometric optics without false precision.
 * Distances are explicitly treated as coarse approximations.
 */
object SpatialDistanceEstimator {

    // Conservative optical constant assuming typical smartphone vertical FOV (~60 deg)
    private const val FOCAL_FACTOR = 1.15f
    // Typical chest/handheld carrying height
    private const val CAMERA_HEIGHT_METERS = 1.35f

    /**
     * Determines the 5-zone lateral spatial direction based on bounding box horizontal centroid.
     * Left / Slight Left / Ahead (Center) / Slight Right / Right
     */
    fun determineSpatialZone(rect: NormalizedRect): SpatialZone {
        val cx = rect.centerX
        return when {
            cx < 0.25f -> SpatialZone.LEFT
            cx < 0.42f -> SpatialZone.SLIGHT_LEFT
            cx <= 0.58f -> SpatialZone.CENTER
            cx <= 0.75f -> SpatialZone.SLIGHT_RIGHT
            else -> SpatialZone.RIGHT
        }
    }

    /**
     * Maps the horizontal position to standard 12-hour clock face positions
     * (9, 10, 11, 12, 1, 2, 3 o'clock).
     */
    fun determineClockDirection(rect: NormalizedRect): Int {
        val cx = rect.centerX
        return when {
            cx < 0.20f -> 9
            cx < 0.35f -> 10
            cx < 0.45f -> 11
            cx <= 0.55f -> 12
            cx <= 0.65f -> 1
            cx <= 0.80f -> 2
            else -> 3
        }
    }

    /**
     * Calculates walking-path corridor overlap.
     * Direct path is defined as the central 40% corridor ([0.30 .. 0.70])
     * occupying the lower and middle visual ground plane (bottom > 0.40).
     */
    fun isDirectWalkingPath(rect: NormalizedRect): Boolean {
        // Horizontal span intersects central walking corridor
        val intersectsCenterCorridor = rect.left < 0.68f && rect.right > 0.32f
        // Vertical ground proximity: object extends below the horizon into immediate walking plane
        val inGroundPlane = rect.bottom > 0.40f
        return intersectsCenterCorridor && inGroundPlane
    }

    /**
     * Estimates conservative approximate distance in meters.
     *
     * Combines two conservative bounds:
     * 1. Apparent angular height scaling based on standard object height priors.
     * 2. Ground-plane contact point projection from the optical horizon.
     *
     * To protect safety, distances are bounded conservatively:
     * - Objects cut off at frame boundaries or close to bottom are bounded.
     * - Returned distances are coarse approximations.
     */
    fun estimateDistanceMeters(category: ObjectCategory, rect: NormalizedRect): Float {
        val clampedHeight = rect.height.coerceIn(0.05f, 1.0f)
        val apparentHeightDist = (category.defaultHeightMeters * FOCAL_FACTOR) / clampedHeight

        // Ground-plane perspective calculation (distance to bottom contact point)
        val groundContactDist = if (rect.bottom > 0.48f) {
            val deltaFromHorizon = (rect.bottom - 0.45f).coerceAtLeast(0.05f)
            CAMERA_HEIGHT_METERS / deltaFromHorizon
        } else {
            12.0f
        }

        // Weighted fusion
        val blended = if (rect.bottom > 0.85f) {
            minOf(apparentHeightDist, groundContactDist)
        } else {
            (apparentHeightDist * 0.70f + groundContactDist * 0.30f)
        }

        return blended.coerceIn(0.5f, 20.0f)
    }

    /**
     * Categorizes continuous distance into 3 conservative semantic buckets:
     * - VERY_CLOSE (< 1.8m, immediate hazard zone)
     * - NEARBY (1.8m .. 4.0m, caution / awareness zone)
     * - FAR (> 4.0m, informational awareness)
     */
    fun categorizeDistance(distanceMeters: Float): DistanceBucket {
        return when {
            distanceMeters < 1.8f -> DistanceBucket.VERY_CLOSE
            distanceMeters <= 4.0f -> DistanceBucket.NEARBY
            else -> DistanceBucket.FAR
        }
    }
}
