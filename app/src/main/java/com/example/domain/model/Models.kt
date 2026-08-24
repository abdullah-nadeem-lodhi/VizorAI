package com.example.domain.model

enum class SpatialZone(val displayName: String) {
    LEFT("left"),
    SLIGHT_LEFT("slightly left"),
    CENTER("ahead"),
    SLIGHT_RIGHT("slightly right"),
    RIGHT("right")
}

enum class DistanceBucket(val displayName: String) {
    VERY_CLOSE("very close"),
    NEARBY("nearby"),
    FAR("ahead")
}

enum class DangerLevel(val levelCode: Int, val title: String) {
    INFORMATION(1, "INFORMATION"),
    CAUTION(2, "CAUTION"),
    IMMEDIATE_HAZARD(3, "IMMEDIATE HAZARD")
}

enum class ObjectCategory(val defaultHeightMeters: Float, val isMobileHazard: Boolean) {
    PERSON(1.70f, true),
    VEHICLE(1.50f, true),
    BICYCLE(1.10f, true),
    MOTORCYCLE(1.20f, true),
    CHAIR(0.85f, false),
    TABLE(0.75f, false),
    DOOR(2.05f, false),
    STAIRS(1.00f, true),
    WALL(2.50f, false),
    POLE(2.20f, false),
    CURB(0.15f, true),
    OBSTACLE(0.80f, false)
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val area: Float get() = width * height
}

data class RawVisionDetection(
    val category: ObjectCategory,
    val label: String,
    val confidence: Float,
    val rect: NormalizedRect,
    val timestamp: Long = System.currentTimeMillis()
)

data class TrackedDetection(
    val trackId: Int,
    val category: ObjectCategory,
    val label: String,
    val confidence: Float,
    val rect: NormalizedRect,
    val distanceMeters: Float,
    val distanceBucket: DistanceBucket,
    val spatialZone: SpatialZone,
    val clockDirection: Int, // 10, 11, 12, 1, 2 o'clock
    val approachVelocityMps: Float, // positive if getting closer
    val isDirectPath: Boolean,
    val persistenceFrames: Int,
    val dangerScore: Float,
    val dangerLevel: DangerLevel,
    val lastUpdatedTimestamp: Long
)

data class GuidanceAlert(
    val id: String,
    val level: DangerLevel,
    val spokenText: String,
    val shortDisplay: String,
    val timestamp: Long = System.currentTimeMillis(),
    val requiresInterruption: Boolean = false
)

data class PerformanceTelemetry(
    val cameraFps: Int = 0,
    val inferenceFps: Int = 0,
    val inferenceLatencyMs: Long = 0,
    val trackingLatencyMs: Long = 0,
    val droppedFramesCount: Long = 0
)

data class GuidanceState(
    val isGuidanceActive: Boolean = false,
    val isAudioMuted: Boolean = false,
    val currentDangerLevel: DangerLevel = DangerLevel.INFORMATION,
    val latestAlert: GuidanceAlert? = null,
    val trackedObjects: List<TrackedDetection> = emptyList(),
    val telemetry: PerformanceTelemetry = PerformanceTelemetry(),
    val isDegradedLocalMode: Boolean = false,
    val sceneDescriptionSummary: String? = null,
    val isDescribingScene: Boolean = false,
    val isListeningForVoiceCommand: Boolean = false,
    val lastVoiceTranscript: String? = null
)
