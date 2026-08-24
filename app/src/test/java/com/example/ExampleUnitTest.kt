package com.example

import com.example.domain.engine.DangerEngine
import com.example.domain.engine.MultiFrameTracker
import com.example.domain.engine.NotificationPriorityEngine
import com.example.domain.engine.SpatialDistanceEstimator
import com.example.domain.model.DangerLevel
import com.example.domain.model.DistanceBucket
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ObjectCategory
import com.example.domain.model.RawVisionDetection
import com.example.domain.model.SpatialZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.io.File

class ExampleUnitTest {

    @Test
    fun testDetectTfliteModelAssetIntegrity() {
        val modelFile = File("src/main/assets/detect.tflite")
        assertTrue("Model file should exist at ${modelFile.absolutePath}", modelFile.exists())
        val bytes = modelFile.readBytes()
        assertTrue("Model file should be > 2MB, was ${bytes.size} bytes", bytes.size > 2_000_000)
        // Verify TFL3 flatbuffer magic header at byte offset 4
        assertEquals('T'.code.toByte(), bytes[4])
        assertEquals('F'.code.toByte(), bytes[5])
        assertEquals('L'.code.toByte(), bytes[6])
        assertEquals('3'.code.toByte(), bytes[7])
        // Verify no UTF-8 corruption replacement characters (EF BF BD)
        var utf8Replacements = 0
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0xEF.toByte() && bytes[i + 1] == 0xBF.toByte() && bytes[i + 2] == 0xBD.toByte()) {
                utf8Replacements++
            }
        }
        assertEquals("Model should contain 0 UTF-8 replacement corruption markers", 0, utf8Replacements)
    }

    @Test
    fun testSpatialDistanceEstimator_DirectionsAndClock() {
        // Left
        val leftRect = NormalizedRect(0.05f, 0.2f, 0.18f, 0.8f)
        assertEquals(SpatialZone.LEFT, SpatialDistanceEstimator.determineSpatialZone(leftRect))
        assertEquals(9, SpatialDistanceEstimator.determineClockDirection(leftRect))
        assertFalse(SpatialDistanceEstimator.isDirectWalkingPath(leftRect))

        // Slight Left
        val slightLeftRect = NormalizedRect(0.25f, 0.2f, 0.40f, 0.8f)
        assertEquals(SpatialZone.SLIGHT_LEFT, SpatialDistanceEstimator.determineSpatialZone(slightLeftRect))
        assertTrue(SpatialDistanceEstimator.determineClockDirection(slightLeftRect) in listOf(10, 11))

        // Ahead / Center
        val centerRect = NormalizedRect(0.40f, 0.2f, 0.60f, 0.8f)
        assertEquals(SpatialZone.CENTER, SpatialDistanceEstimator.determineSpatialZone(centerRect))
        assertEquals(12, SpatialDistanceEstimator.determineClockDirection(centerRect))
        assertTrue(SpatialDistanceEstimator.isDirectWalkingPath(centerRect))

        // Slight Right
        val slightRightRect = NormalizedRect(0.60f, 0.2f, 0.75f, 0.8f)
        assertEquals(SpatialZone.SLIGHT_RIGHT, SpatialDistanceEstimator.determineSpatialZone(slightRightRect))
        assertTrue(SpatialDistanceEstimator.determineClockDirection(slightRightRect) in listOf(1, 2))

        // Right
        val rightRect = NormalizedRect(0.82f, 0.2f, 0.95f, 0.8f)
        assertEquals(SpatialZone.RIGHT, SpatialDistanceEstimator.determineSpatialZone(rightRect))
        assertEquals(3, SpatialDistanceEstimator.determineClockDirection(rightRect))
        assertFalse(SpatialDistanceEstimator.isDirectWalkingPath(rightRect))
    }

    @Test
    fun testSpatialDistanceEstimator_DistanceBucketsAndConservativeEstimates() {
        // Very close large object filling 90% of screen height
        val closePersonRect = NormalizedRect(0.1f, 0.05f, 0.9f, 0.95f)
        val closeDist = SpatialDistanceEstimator.estimateDistanceMeters(ObjectCategory.PERSON, closePersonRect)
        assertTrue("Close person should be estimated < 2.5m, got $closeDist", closeDist < 2.5f)

        // Moderate distance person in midground
        val midPersonRect = NormalizedRect(0.4f, 0.35f, 0.6f, 0.75f)
        val midDist = SpatialDistanceEstimator.estimateDistanceMeters(ObjectCategory.PERSON, midPersonRect)
        assertTrue("Midground person should be between 2.5m and 6.0m, got $midDist", midDist in 2.5f..6.0f)

        // Far tiny person near horizon
        val farPersonRect = NormalizedRect(0.48f, 0.44f, 0.52f, 0.52f)
        val farDist = SpatialDistanceEstimator.estimateDistanceMeters(ObjectCategory.PERSON, farPersonRect)
        assertTrue("Far person should be estimated >= 6.0m, got $farDist", farDist >= 6.0f)
        assertEquals(DistanceBucket.FAR, SpatialDistanceEstimator.categorizeDistance(farDist))
    }

    // 1. Distant parked vehicle -> Low Danger (Level 1 Information)
    @Test
    fun testDangerScenario1_DistantParkedVehicle() {
        val (score, level) = DangerEngine.computeDanger(
            category = ObjectCategory.VEHICLE,
            distanceMeters = 8.5f,
            isDirectPath = false,
            approachVelocityMps = 0.0f,
            persistenceFrames = 5,
            confidence = 0.85f
        )
        assertEquals(DangerLevel.INFORMATION, level)
        assertTrue("Score for distant vehicle should be < 38", score < 38.0f)
    }

    // 2. Close obstacle in path -> Level 2 or Level 3
    @Test
    fun testDangerScenario2_CloseObstacleInPath() {
        val (score, level) = DangerEngine.computeDanger(
            category = ObjectCategory.OBSTACLE,
            distanceMeters = 1.0f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 4,
            confidence = 0.88f
        )
        assertTrue("Close obstacle in path must be at least Level 2 (Caution) or Level 3 (Hazard)",
            level == DangerLevel.CAUTION || level == DangerLevel.IMMEDIATE_HAZARD)
        assertTrue(score >= 68.0f)
    }

    // 3. Approaching person -> Escalation from Information to Caution/Hazard
    @Test
    fun testDangerScenario3_ApproachingPersonEscalation() {
        // Stationary person at 3.0m
        val (stationaryScore, stationaryLevel) = DangerEngine.computeDanger(
            category = ObjectCategory.PERSON,
            distanceMeters = 3.0f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 3,
            confidence = 0.85f
        )

        // Rapidly approaching person at 3.0m (velocity = 1.5 m/s)
        val (approachingScore, approachingLevel) = DangerEngine.computeDanger(
            category = ObjectCategory.PERSON,
            distanceMeters = 3.0f,
            isDirectPath = true,
            approachVelocityMps = 1.5f,
            persistenceFrames = 3,
            confidence = 0.85f
        )

        assertTrue("Approaching person score must be significantly higher", approachingScore > stationaryScore + 20f)
        assertTrue(approachingScore >= DangerEngine.ESCALATE_TO_HAZARD_SCORE)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, approachingLevel)
    }

    // 4. Approaching vehicle -> Escalation to Level 3 Immediate Hazard
    @Test
    fun testDangerScenario4_ApproachingVehicleImmediateHazard() {
        val (score, level) = DangerEngine.computeDanger(
            category = ObjectCategory.VEHICLE,
            distanceMeters = 3.5f,
            isDirectPath = true,
            approachVelocityMps = 1.8f,
            persistenceFrames = 4,
            confidence = 0.92f
        )
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, level)
        assertTrue(score >= DangerEngine.ESCALATE_TO_HAZARD_SCORE)
    }

    // 5. Object outside walking path -> Reduced priority compared to direct path
    @Test
    fun testDangerScenario5_OutsidePathReducedPriority() {
        val (inPathScore, inPathLevel) = DangerEngine.computeDanger(
            category = ObjectCategory.CHAIR,
            distanceMeters = 2.0f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 3,
            confidence = 0.80f
        )

        val (outsidePathScore, outsidePathLevel) = DangerEngine.computeDanger(
            category = ObjectCategory.CHAIR,
            distanceMeters = 2.0f,
            isDirectPath = false,
            approachVelocityMps = 0.0f,
            persistenceFrames = 3,
            confidence = 0.80f
        )

        assertTrue("In-path chair must have higher score than outside-path", inPathScore > outsidePathScore)
        assertTrue(inPathScore - outsidePathScore >= 25f)
        assertEquals(DangerLevel.CAUTION, inPathLevel)
        assertEquals(DangerLevel.INFORMATION, outsidePathLevel)
    }

    // 6. Low-confidence detection -> Conservative warning with reduced score
    @Test
    fun testDangerScenario6_LowConfidenceConservativeWarning() {
        val (highConfScore, _) = DangerEngine.computeDanger(
            category = ObjectCategory.OBSTACLE,
            distanceMeters = 2.0f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 4,
            confidence = 0.85f
        )

        val (lowConfScore, _) = DangerEngine.computeDanger(
            category = ObjectCategory.OBSTACLE,
            distanceMeters = 2.0f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 4,
            confidence = 0.40f // low confidence
        )

        assertTrue("Low confidence detection must be attenuated", lowConfScore < highConfScore)

        // Verify speech engine formats low-confidence detection as "Possible [label]"
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()
        val lowConfDetections = listOf(
            RawVisionDetection(
                category = ObjectCategory.CHAIR,
                label = "Chair",
                confidence = 0.45f,
                rect = NormalizedRect(0.1f, 0.3f, 0.3f, 0.7f)
            )
        )
        tracker.update(lowConfDetections, 1000L)
        val tracked = tracker.update(lowConfDetections, 1100L)
        val alert = priorityEngine.evaluateNextAlert(tracked, 1100L)
        assertNotNull(alert)
        assertTrue("Speech must contain 'Possible' for low confidence", alert!!.spokenText.contains("Possible"))
    }

    // 7. Object moving away -> No unnecessary escalation
    @Test
    fun testDangerScenario7_ObjectMovingAway() {
        // Stationary person at 2.5m
        val (stationaryScore, _) = DangerEngine.computeDanger(
            category = ObjectCategory.PERSON,
            distanceMeters = 2.5f,
            isDirectPath = true,
            approachVelocityMps = 0.0f,
            persistenceFrames = 3,
            confidence = 0.80f
        )

        // Person walking away (receding, negative velocity -1.0 m/s)
        val (recedingScore, recedingLevel) = DangerEngine.computeDanger(
            category = ObjectCategory.PERSON,
            distanceMeters = 2.5f,
            isDirectPath = true,
            approachVelocityMps = -1.0f,
            persistenceFrames = 3,
            confidence = 0.80f
        )

        assertTrue("Receding object must have lower danger score", recedingScore < stationaryScore)
        assertEquals(DangerLevel.INFORMATION, recedingLevel)
    }

    // 8. Multiple objects -> Most dangerous object wins
    @Test
    fun testDangerScenario8_MultipleObjectsMostDangerousWins() {
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()

        val multiDetections = listOf(
            RawVisionDetection(
                category = ObjectCategory.CHAIR,
                label = "Chair",
                confidence = 0.85f,
                rect = NormalizedRect(0.8f, 0.4f, 0.95f, 0.6f)
            ),
            RawVisionDetection(
                category = ObjectCategory.PERSON,
                label = "Person",
                confidence = 0.85f,
                rect = NormalizedRect(0.1f, 0.2f, 0.3f, 0.7f)
            ),
            RawVisionDetection(
                category = ObjectCategory.VEHICLE,
                label = "Car",
                confidence = 0.90f,
                rect = NormalizedRect(0.35f, 0.1f, 0.65f, 0.85f)
            )
        )

        // Warm up tracker
        tracker.update(multiDetections, 1000L)
        val tracked = tracker.update(multiDetections, 1100L)

        val alert = priorityEngine.evaluateNextAlert(tracked, 1100L)
        assertNotNull(alert)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, alert!!.level)
        assertTrue("Winner must be Car", alert.spokenText.contains("Car") || alert.spokenText.contains("car"))
    }

    // 9. Warning Fatigue Simulation Test (Iteration 6 Requirement)
    @Test
    fun testWarningFatigue_Simulation() {
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()

        // Complex crowded scene simulation:
        // - Multiple background people (lateral left & right)
        // - Multiple parked vehicles (lateral far)
        // - Background furniture (chair, table far right)
        // - ONE obstacle in direct walking path (chair at 1.8m ahead)
        // - ONE fast approaching motorcycle (approaching ahead at 1.6 m/s)
        val crowdedFrame1 = listOf(
            RawVisionDetection(ObjectCategory.PERSON, "Person 1", 0.80f, NormalizedRect(0.05f, 0.3f, 0.20f, 0.7f)),
            RawVisionDetection(ObjectCategory.PERSON, "Person 2", 0.75f, NormalizedRect(0.80f, 0.3f, 0.95f, 0.7f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Parked Car 1", 0.85f, NormalizedRect(0.02f, 0.4f, 0.25f, 0.6f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Parked Car 2", 0.82f, NormalizedRect(0.78f, 0.4f, 0.98f, 0.6f)),
            RawVisionDetection(ObjectCategory.CHAIR, "Distant Chair", 0.70f, NormalizedRect(0.85f, 0.5f, 0.95f, 0.7f)),
            RawVisionDetection(ObjectCategory.OBSTACLE, "Path Obstacle", 0.88f, NormalizedRect(0.42f, 0.4f, 0.58f, 0.85f)),
            RawVisionDetection(ObjectCategory.MOTORCYCLE, "Motorcycle", 0.92f, NormalizedRect(0.38f, 0.2f, 0.62f, 0.70f))
        )

        // Warm up frame 1
        tracker.update(crowdedFrame1, 1000L)

        // Frame 2 with motorcycle moving closer
        val crowdedFrame2 = listOf(
            RawVisionDetection(ObjectCategory.PERSON, "Person 1", 0.80f, NormalizedRect(0.05f, 0.3f, 0.20f, 0.7f)),
            RawVisionDetection(ObjectCategory.PERSON, "Person 2", 0.75f, NormalizedRect(0.80f, 0.3f, 0.95f, 0.7f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Parked Car 1", 0.85f, NormalizedRect(0.02f, 0.4f, 0.25f, 0.6f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Parked Car 2", 0.82f, NormalizedRect(0.78f, 0.4f, 0.98f, 0.6f)),
            RawVisionDetection(ObjectCategory.CHAIR, "Distant Chair", 0.70f, NormalizedRect(0.85f, 0.5f, 0.95f, 0.7f)),
            RawVisionDetection(ObjectCategory.OBSTACLE, "Path Obstacle", 0.88f, NormalizedRect(0.42f, 0.4f, 0.58f, 0.85f)),
            RawVisionDetection(ObjectCategory.MOTORCYCLE, "Motorcycle", 0.95f, NormalizedRect(0.35f, 0.1f, 0.65f, 0.85f)) // closer
        )
        val tracked = tracker.update(crowdedFrame2, 1100L)

        // Verify only ONE alert is generated for the highest priority hazard (Motorcycle)
        val alert1 = priorityEngine.evaluateNextAlert(tracked, 1100L)
        assertNotNull(alert1)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, alert1!!.level)
        assertTrue("Speech must be for Motorcycle", alert1.spokenText.contains("Motorcycle") || alert1.spokenText.contains("motorcycle"))
        assertTrue("Critical hazard must require speech interruption", alert1.requiresInterruption)

        // Subsequent frame 100ms later with static scene must NOT chatter/flood announcements
        val alert2 = priorityEngine.evaluateNextAlert(tracked, 1200L)
        assertNull("Static objects must be throttled by cooldown to prevent warning fatigue", alert2)
    }

    // 10. Speech Interruption on Critical Hazard
    @Test
    fun testCriticalSpeechInterruptsLowerPriority() {
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()

        // Caution obstacle
        val cautionObj = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.80f, NormalizedRect(0.4f, 0.4f, 0.6f, 0.75f))
        )
        tracker.update(cautionObj, 1000L)
        val trackedCaution = tracker.update(cautionObj, 1100L)
        val cautionAlert = priorityEngine.evaluateNextAlert(trackedCaution, 1100L)
        assertNotNull(cautionAlert)
        assertFalse("Caution alert should not force queue flush interruption", cautionAlert!!.requiresInterruption)

        // Critical vehicle approaching fast
        val hazardObj = listOf(
            RawVisionDetection(ObjectCategory.VEHICLE, "Car", 0.95f, NormalizedRect(0.35f, 0.1f, 0.65f, 0.9f))
        )
        tracker.update(hazardObj, 1300L)
        val trackedHazard = tracker.update(hazardObj, 1400L)
        val hazardAlert = priorityEngine.evaluateNextAlert(trackedHazard, 1400L)
        assertNotNull(hazardAlert)
        assertTrue("Immediate hazard MUST force interruption of current speech", hazardAlert!!.requiresInterruption)
    }

    // 11. Voice Command Parser - Valid "Describe surroundings" & variants
    @Test
    fun testVoiceCommand_ValidDescribeSurroundings() {
        val cmd1 = com.example.domain.engine.VoiceCommandParser.parse("Describe surroundings.")
        assertEquals(com.example.domain.engine.VoiceCommand.DESCRIBE_SURROUNDINGS, cmd1)

        val cmd2 = com.example.domain.engine.VoiceCommandParser.parse("what's around me?")
        assertEquals(com.example.domain.engine.VoiceCommand.DESCRIBE_SURROUNDINGS, cmd2)

        val cmd3 = com.example.domain.engine.VoiceCommandParser.parse("tell me surroundings")
        assertEquals(com.example.domain.engine.VoiceCommand.DESCRIBE_SURROUNDINGS, cmd3)

        val cmd4 = com.example.domain.engine.VoiceCommandParser.parse("surroundings")
        assertEquals(com.example.domain.engine.VoiceCommand.DESCRIBE_SURROUNDINGS, cmd4)
    }

    // 12. Voice Command Parser - Unknown / Recognition Failure
    @Test
    fun testVoiceCommand_RecognitionFailure() {
        val cmd1 = com.example.domain.engine.VoiceCommandParser.parse("play music")
        assertEquals(com.example.domain.engine.VoiceCommand.UNKNOWN, cmd1)

        val cmd2 = com.example.domain.engine.VoiceCommandParser.parse("")
        assertEquals(com.example.domain.engine.VoiceCommand.UNKNOWN, cmd2)

        val cmd3 = com.example.domain.engine.VoiceCommandParser.parse(null)
        assertEquals(com.example.domain.engine.VoiceCommand.UNKNOWN, cmd3)
    }

    // 13. Environment Summary - Multiple Objects
    @Test
    fun testEnvironmentSummary_MultipleObjects() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.PERSON, "Person", 0.90f, NormalizedRect(0.42f, 0.2f, 0.58f, 0.8f)), // center/ahead
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.22f, 0.4f, 0.38f, 0.7f)), // slightly left
            RawVisionDetection(ObjectCategory.VEHICLE, "Vehicle", 0.88f, NormalizedRect(0.82f, 0.4f, 0.98f, 0.6f)) // right
        )

        // Warm up tracker
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val summary = com.example.domain.engine.EnvironmentSummaryEngine.generateSummary(tracked)
        assertNotNull(summary)
        assertTrue("Summary must start with 'There is'", summary.startsWith("There is"))
        assertTrue("Summary should contain person", summary.contains("person", ignoreCase = true))
        assertTrue("Summary should contain chair", summary.contains("chair", ignoreCase = true))
        assertTrue("Summary should contain vehicle", summary.contains("vehicle", ignoreCase = true))
        assertFalse("Summary must NEVER say safe", summary.contains("safe", ignoreCase = true))
        assertFalse("Summary must NEVER say clear", summary.contains("clear to walk", ignoreCase = true))
    }

    // 14. Environment Summary - No Objects Detected
    @Test
    fun testEnvironmentSummary_NoObjectsDetected() {
        val summary = com.example.domain.engine.EnvironmentSummaryEngine.generateSummary(emptyList())
        assertEquals("I couldn't identify enough of the surroundings.", summary)
    }

    // 15. Environment Summary - Low Confidence Uncertainty
    @Test
    fun testEnvironmentSummary_LowConfidenceUncertainty() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.45f, NormalizedRect(0.42f, 0.4f, 0.58f, 0.75f))
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val summary = com.example.domain.engine.EnvironmentSummaryEngine.generateSummary(tracked)
        assertTrue("Summary must note uncertainty using 'possible'", summary.contains("possible", ignoreCase = true))
    }

    // 16. Continuous Hazard Pipeline Continues During Voice Commands
    @Test
    fun testContinuousHazardDetectionDuringVoiceCommand() {
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()

        // 1. Initial stationary objects
        val initialDetections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.1f, 0.3f, 0.25f, 0.6f))
        )
        tracker.update(initialDetections, 1000L)
        val tracked1 = tracker.update(initialDetections, 1100L)

        // Generate environment summary (runs in parallel/background without altering tracker state)
        val summary = com.example.domain.engine.EnvironmentSummaryEngine.generateSummary(tracked1)
        assertTrue(summary.contains("chair", ignoreCase = true))

        // 2. Sudden incoming vehicle while summary is being prepared/spoken
        val hazardDetections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.1f, 0.3f, 0.25f, 0.6f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Car", 0.95f, NormalizedRect(0.35f, 0.1f, 0.65f, 0.90f))
        )
        tracker.update(hazardDetections, 1200L)
        val trackedHazard = tracker.update(hazardDetections, 1300L)

        val criticalAlert = priorityEngine.evaluateNextAlert(trackedHazard, 1300L)
        assertNotNull("Continuous vision pipeline must immediately detect oncoming hazard", criticalAlert)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, criticalAlert!!.level)
        assertTrue("Immediate hazard must force interruption of any ongoing speech/summary", criticalAlert.requiresInterruption)
    }

    // 17. Valid Find Object command parsing
    @Test
    fun testFindObject_ValidCommandParsing() {
        val result = com.example.domain.engine.VoiceCommandParser.parseCommand("Find the chair.")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, result.command)
        assertEquals("chair", result.targetObject)
    }

    // 18. Natural Find Object command variations
    @Test
    fun testFindObject_NaturalCommandVariations() {
        val r1 = com.example.domain.engine.VoiceCommandParser.parseCommand("Where is the chair?")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r1.command)
        assertEquals("chair", r1.targetObject)

        val r2 = com.example.domain.engine.VoiceCommandParser.parseCommand("Find the person.")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r2.command)
        assertEquals("person", r2.targetObject)

        val r3 = com.example.domain.engine.VoiceCommandParser.parseCommand("Where is the car?")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r3.command)
        assertEquals("car", r3.targetObject)

        val r4 = com.example.domain.engine.VoiceCommandParser.parseCommand("Can you find a chair")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r4.command)
        assertEquals("chair", r4.targetObject)

        val r5 = com.example.domain.engine.VoiceCommandParser.parseCommand("Locate the backpack")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r5.command)
        assertEquals("backpack", r5.targetObject)

        val r6 = com.example.domain.engine.VoiceCommandParser.parseCommand("Where's my bottle")
        assertEquals(com.example.domain.engine.VoiceCommand.FIND_OBJECT, r6.command)
        assertEquals("bottle", r6.targetObject)
    }

    // 19. Object Found - Single match with direction and distance
    @Test
    fun testFindObject_SingleMatchFound() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.65f, 0.35f, 0.85f, 0.75f)) // slightly right, ~2-3m
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val response = com.example.domain.engine.FindObjectEngine.executeFind("chair", tracked)
        assertNotNull(response)
        assertTrue("Response should contain object name", response.contains("Chair", ignoreCase = true))
        assertTrue("Response should contain spatial direction", response.contains("right", ignoreCase = true))
        assertTrue("Response should contain distance", response.contains("meter") || response.contains("ahead") || response.contains("nearby"))
        assertFalse("Must never claim path is clear or safe", response.contains("safe", ignoreCase = true))
    }

    // 20. Object Not Found
    @Test
    fun testFindObject_NotFound() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.PERSON, "Person", 0.90f, NormalizedRect(0.4f, 0.2f, 0.6f, 0.8f))
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val response = com.example.domain.engine.FindObjectEngine.executeFind("chair", tracked)
        assertEquals("I don't currently see a chair.", response)
    }

    // 21. Multiple Matching Objects
    @Test
    fun testFindObject_MultipleMatchingObjects() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.PERSON, "Person", 0.92f, NormalizedRect(0.42f, 0.2f, 0.58f, 0.8f)), // center/ahead
            RawVisionDetection(ObjectCategory.PERSON, "Person", 0.88f, NormalizedRect(0.05f, 0.2f, 0.25f, 0.8f))  // left
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val response = com.example.domain.engine.FindObjectEngine.executeFind("person", tracked)
        assertNotNull(response)
        assertTrue("Response must indicate count of 2", response.contains("2 people", ignoreCase = true) || response.contains("two people", ignoreCase = true))
        assertTrue("Response should describe first person position", response.contains("ahead", ignoreCase = true) || response.contains("center", ignoreCase = true))
        assertTrue("Response should describe second person position", response.contains("left", ignoreCase = true))
    }

    // 22. Unsupported Object Request (Door, Stairs, Curb, Drop-off)
    @Test
    fun testFindObject_UnsupportedObjects() {
        val rDoor = com.example.domain.engine.FindObjectEngine.executeFind("door", emptyList())
        assertEquals("I can't reliably search for that object yet.", rDoor)

        val rStairs = com.example.domain.engine.FindObjectEngine.executeFind("stairs", emptyList())
        assertEquals("I can't reliably search for that object yet.", rStairs)

        val rCurb = com.example.domain.engine.FindObjectEngine.executeFind("curb", emptyList())
        assertEquals("I can't reliably search for that object yet.", rCurb)

        val rDropoff = com.example.domain.engine.FindObjectEngine.executeFind("drop off", emptyList())
        assertEquals("I can't reliably search for that object yet.", rDropoff)
    }

    // 23. Low-confidence Detection uncertainty prefix
    @Test
    fun testFindObject_LowConfidenceDetection() {
        val tracker = MultiFrameTracker()
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.45f, NormalizedRect(0.65f, 0.35f, 0.85f, 0.75f))
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val response = com.example.domain.engine.FindObjectEngine.executeFind("chair", tracked)
        assertTrue("Response must include 'Possible' prefix for low confidence", response.startsWith("Possible"))
    }

    // 24. Level 3 Hazard interrupts Find Object interaction
    @Test
    fun testFindObject_HazardInterruptsFindSpeech() {
        val priorityEngine = NotificationPriorityEngine()
        val tracker = MultiFrameTracker()

        // 1. User is searching for a chair in the room
        val stationaryDetections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.7f, 0.4f, 0.9f, 0.8f))
        )
        tracker.update(stationaryDetections, 1000L)
        val trackedChair = tracker.update(stationaryDetections, 1100L)

        val findResponse = com.example.domain.engine.FindObjectEngine.executeFind("chair", trackedChair)
        assertTrue(findResponse.contains("Chair", ignoreCase = true))

        // 2. Sudden fast approaching hazard while find response is active
        val oncomingHazard = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.7f, 0.4f, 0.9f, 0.8f)),
            RawVisionDetection(ObjectCategory.MOTORCYCLE, "Motorcycle", 0.98f, NormalizedRect(0.35f, 0.05f, 0.65f, 0.95f))
        )
        tracker.update(oncomingHazard, 1200L)
        val trackedHazard = tracker.update(oncomingHazard, 1300L)

        val criticalAlert = priorityEngine.evaluateNextAlert(trackedHazard, 1300L)
        assertNotNull("Critical hazard must be dispatched", criticalAlert)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, criticalAlert!!.level)
        assertTrue("Hazard alert MUST specify requiresInterruption = true to flush Find Object speech", criticalAlert.requiresInterruption)
    }

    // 25. Selection logic preference (Confidence, Distance, Path relevance, Persistence)
    @Test
    fun testFindObject_SelectionRankingPreference() {
        val tracker = MultiFrameTracker()
        // Two chairs: one further away (0.3m width => ~4m) with high confidence, one closer (0.6m width => ~2m) directly in path
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.92f, NormalizedRect(0.40f, 0.2f, 0.60f, 0.9f)), // close in path (higher priority)
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.70f, NormalizedRect(0.10f, 0.3f, 0.20f, 0.5f))  // far lateral
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val response = com.example.domain.engine.FindObjectEngine.executeFind("chair", tracked)
        assertTrue("Multi-object response accurately accounts for both matches", response.contains("2 chairs") || response.contains("two chairs"))
    }

    // 26. Safe Path: Center blocked, right less obstructed
    @Test
    fun testSafePath_CenterBlocked_RightLessObstructed() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        // Obstacles in center and left; right is open
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)), // center
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.05f, 0.2f, 0.30f, 0.7f))  // left
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val result = pathEngine.evaluatePath(tracked)
        assertEquals(com.example.domain.engine.RecommendedDirection.MOVE_RIGHT, result.recommendedDirection)
        assertTrue("Response suggests right direction", result.spokenText.contains("right", ignoreCase = true))
        assertFalse("Response must NEVER claim path is safe", result.spokenText.contains("is safe", ignoreCase = true))
    }

    // 27. Safe Path: Center blocked, left less obstructed
    @Test
    fun testSafePath_CenterBlocked_LeftLessObstructed() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        // Obstacles in center and right; left is open
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)), // center
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.70f, 0.2f, 0.95f, 0.7f))  // right
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val result = pathEngine.evaluatePath(tracked)
        assertEquals(com.example.domain.engine.RecommendedDirection.MOVE_LEFT, result.recommendedDirection)
        assertTrue("Response suggests left direction", result.spokenText.contains("left", ignoreCase = true))
        assertFalse("Response must NEVER claim path is safe", result.spokenText.contains("is safe", ignoreCase = true))
    }

    // 28. Safe Path: Both sides obstructed
    @Test
    fun testSafePath_BothSidesObstructed() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        // Heavy obstacles in center, left, and right
        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.88f, NormalizedRect(0.05f, 0.2f, 0.30f, 0.7f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.88f, NormalizedRect(0.05f, 0.4f, 0.30f, 0.9f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Car", 0.92f, NormalizedRect(0.70f, 0.2f, 0.95f, 0.8f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Car", 0.92f, NormalizedRect(0.70f, 0.4f, 0.95f, 0.9f))
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val result = pathEngine.evaluatePath(tracked)
        assertEquals(com.example.domain.engine.RecommendedDirection.BLOCKED_NO_DIRECTION, result.recommendedDirection)
        assertTrue(result.spokenText.contains("I can't determine a preferred direction", ignoreCase = true) ||
                result.spokenText.contains("obstructed", ignoreCase = true))
    }

    // 29. Safe Path: Insufficient detections
    @Test
    fun testSafePath_InsufficientDetections() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val result = pathEngine.evaluatePath(emptyList())

        assertEquals(com.example.domain.engine.RecommendedDirection.LIMITED_VISIBILITY, result.recommendedDirection)
        assertTrue(result.spokenText.contains("Limited visibility", ignoreCase = true))
        assertFalse("Must never interpret empty detections as proof of safety", result.spokenText.contains("safe", ignoreCase = true))
    }

    // 30. Safe Path: Low confidence detections
    @Test
    fun testSafePath_LowConfidenceDetections() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        val lowConfidenceDetections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.25f, NormalizedRect(0.4f, 0.3f, 0.6f, 0.8f))
        )
        val tracked = tracker.update(lowConfidenceDetections, 1000L)

        val result = pathEngine.evaluatePath(tracked)
        assertEquals(com.example.domain.engine.RecommendedDirection.LIMITED_VISIBILITY, result.recommendedDirection)
        assertTrue(result.spokenText.contains("Limited visibility", ignoreCase = true))
    }

    // 31. Safe Path: Rapid camera movement / unstable view
    @Test
    fun testSafePath_RapidCameraMovement() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        val detections = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f))
        )
        tracker.update(detections, 1000L)
        val tracked = tracker.update(detections, 1100L)

        val result = pathEngine.evaluatePath(tracked, isRapidCameraMotion = true)
        assertEquals(com.example.domain.engine.RecommendedDirection.LIMITED_VISIBILITY, result.recommendedDirection)
        assertTrue(result.spokenText.contains("Limited visibility", ignoreCase = true))
    }

    // 32. Safe Path: Recommendation updates when obstacles move
    @Test
    fun testSafePath_DynamicUpdatesOnObstacleMovement() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        // Frame 1-2: Left blocked, right open -> Move right
        val det1 = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.05f, 0.2f, 0.30f, 0.7f))
        )
        tracker.update(det1, 1000L)
        val tracked1 = tracker.update(det1, 1100L)
        val r1 = pathEngine.evaluatePath(tracked1, currentTimeMs = 1100L)
        assertEquals(com.example.domain.engine.RecommendedDirection.MOVE_RIGHT, r1.recommendedDirection)
        assertTrue(pathEngine.shouldAnnounce(r1, currentTimeMs = 1100L))

        // Frame 3-4: Obstacles shifted to right, left is now open -> Move left
        val det2 = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.70f, 0.2f, 0.95f, 0.7f))
        )
        tracker.update(det2, 2000L)
        val tracked2 = tracker.update(det2, 2100L)
        val r2 = pathEngine.evaluatePath(tracked2, currentTimeMs = 2100L)
        assertEquals(com.example.domain.engine.RecommendedDirection.MOVE_LEFT, r2.recommendedDirection)
        assertTrue("Direction change immediately announces new recommendation", pathEngine.shouldAnnounce(r2, currentTimeMs = 2100L))
    }

    // 33. Safe Path: Identical recommendation is deduplicated
    @Test
    fun testSafePath_IdenticalRecommendationDeduplicated() {
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        val det = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.05f, 0.2f, 0.30f, 0.7f))
        )
        tracker.update(det, 1000L)
        val tracked = tracker.update(det, 1100L)

        val r1 = pathEngine.evaluatePath(tracked, currentTimeMs = 1100L)
        assertTrue("First evaluation should announce", pathEngine.shouldAnnounce(r1, currentTimeMs = 1100L))

        val r2 = pathEngine.evaluatePath(tracked, currentTimeMs = 1200L)
        assertFalse("Repeated identical evaluation within cooldown should be deduplicated", pathEngine.shouldAnnounce(r2, currentTimeMs = 1200L))
    }

    // 34. Safe Path: Level 3 hazard overrides path guidance
    @Test
    fun testSafePath_Level3HazardOverridesPathGuidance() {
        val priorityEngine = NotificationPriorityEngine()
        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        // 1. Initial path guidance suggests moving right
        val det1 = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.TABLE, "Table", 0.85f, NormalizedRect(0.05f, 0.2f, 0.30f, 0.7f))
        )
        tracker.update(det1, 1000L)
        val tracked1 = tracker.update(det1, 1100L)
        val pathResult = pathEngine.evaluatePath(tracked1)
        assertEquals(com.example.domain.engine.RecommendedDirection.MOVE_RIGHT, pathResult.recommendedDirection)

        // 2. Sudden approaching vehicle (Level 3 hazard) appears
        val detHazard = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.85f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f)),
            RawVisionDetection(ObjectCategory.VEHICLE, "Car", 0.98f, NormalizedRect(0.35f, 0.05f, 0.65f, 0.95f))
        )
        tracker.update(detHazard, 1200L)
        val trackedHazard = tracker.update(detHazard, 1300L)

        val criticalAlert = priorityEngine.evaluateNextAlert(trackedHazard, 1300L)
        assertNotNull(criticalAlert)
        assertEquals(DangerLevel.IMMEDIATE_HAZARD, criticalAlert!!.level)
        assertTrue("Level 3 hazard requires immediate speech interruption", criticalAlert.requiresInterruption)
    }

    // 35. Safe Path: Forbidden safety claims verification
    @Test
    fun testSafePath_StrictForbiddenClaimsVerification() {
        val forbiddenPhrases = listOf(
            "the path is safe",
            "it is safe to walk",
            "clear path",
            "go right, it's safe",
            "go left, it's safe",
            "safe to walk",
            "you can walk safely",
            "safe path"
        )

        val pathEngine = com.example.domain.engine.SafePathGuidanceEngine()
        val tracker = MultiFrameTracker()

        val det = listOf(
            RawVisionDetection(ObjectCategory.CHAIR, "Chair", 0.90f, NormalizedRect(0.40f, 0.3f, 0.60f, 0.8f))
        )
        tracker.update(det, 1000L)
        val tracked = tracker.update(det, 1100L)
        val result = pathEngine.evaluatePath(tracked)

        val spokenLower = result.spokenText.lowercase()
        for (phrase in forbiddenPhrases) {
            assertFalse("Forbidden phrase '$phrase' must never appear in path guidance output", spokenLower.contains(phrase))
        }
    }
}
