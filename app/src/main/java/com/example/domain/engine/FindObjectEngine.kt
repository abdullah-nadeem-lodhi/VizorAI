package com.example.domain.engine

import com.example.domain.model.DistanceBucket
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ObjectCategory
import com.example.domain.model.SpatialZone
import com.example.domain.model.TrackedDetection

/**
 * Deterministic Find Object Engine (Iteration 8).
 *
 * Handles voice-driven object localization within currently tracked vision detections.
 * Reuses the existing TFLite detector outputs, MultiFrameTracker, and SpatialDistanceEstimator.
 *
 * STRICT SAFETY & ACCESSIBILITY RULES:
 * - NEVER claims "The path is clear", "The object is safe", or "You can walk there".
 * - Responds "I can't reliably search for that object yet." for unsupported non-COCO classes (e.g. doors, stairs, curbs).
 * - Responds "I don't currently see a [object]." when supported object is not visible.
 * - Prioritizes closest/in-path/highest confidence detection.
 */
object FindObjectEngine {

    // Unsupported object categories that standard COCO MobileNet SSD cannot reliably detect
    private val unsupportedVocabulary = setOf(
        "door", "doors", "front door", "back door",
        "stair", "stairs", "staircase", "steps", "step",
        "curb", "curbs", "sidewalk", "crosswalk",
        "dropoff", "drop-off", "drop off", "ledge", "hole",
        "puddle", "water", "ice",
        "elevator", "escalator", "ramp",
        "window", "glass", "mirror",
        "key", "keys", "wallet", "money", "coin", "card",
        "outlet", "switch", "ceiling", "floor", "ground", "sky"
    )

    // Supported COCO categories mapped to canonical target names and search synonyms
    private val supportedObjectMap = mapOf(
        "person" to listOf("person", "people", "human", "man", "woman", "someone", "anybody", "pedestrian"),
        "car" to listOf("car", "vehicle", "automobile", "auto", "cab", "taxi"),
        "bus" to listOf("bus"),
        "truck" to listOf("truck", "lorry", "pickup"),
        "motorcycle" to listOf("motorcycle", "motorbike", "scooter"),
        "bicycle" to listOf("bicycle", "bike", "cycle"),
        "chair" to listOf("chair", "seat", "armchair"),
        "couch" to listOf("couch", "sofa", "lounge"),
        "table" to listOf("table", "desk", "dining table"),
        "bench" to listOf("bench"),
        "traffic light" to listOf("traffic light", "traffic signal", "stoplight"),
        "stop sign" to listOf("stop sign"),
        "fire hydrant" to listOf("fire hydrant", "hydrant"),
        "backpack" to listOf("backpack", "bag", "knapsack", "schoolbag"),
        "umbrella" to listOf("umbrella", "parasol"),
        "handbag" to listOf("handbag", "purse"),
        "suitcase" to listOf("suitcase", "luggage"),
        "bottle" to listOf("bottle", "water bottle"),
        "cup" to listOf("cup", "mug"),
        "fork" to listOf("fork"),
        "knife" to listOf("knife"),
        "spoon" to listOf("spoon"),
        "bowl" to listOf("bowl"),
        "banana" to listOf("banana"),
        "apple" to listOf("apple"),
        "sandwich" to listOf("sandwich"),
        "orange" to listOf("orange"),
        "broccoli" to listOf("broccoli"),
        "carrot" to listOf("carrot"),
        "hot dog" to listOf("hot dog", "hotdog"),
        "pizza" to listOf("pizza"),
        "donut" to listOf("donut", "doughnut"),
        "cake" to listOf("cake"),
        "potted plant" to listOf("plant", "potted plant", "flowerpot", "flower pot"),
        "bed" to listOf("bed"),
        "toilet" to listOf("toilet", "restroom"),
        "tv" to listOf("tv", "television", "monitor", "screen"),
        "laptop" to listOf("laptop", "computer"),
        "mouse" to listOf("mouse"),
        "remote" to listOf("remote", "controller"),
        "keyboard" to listOf("keyboard"),
        "cell phone" to listOf("phone", "cell phone", "cellphone", "mobile"),
        "microwave" to listOf("microwave"),
        "oven" to listOf("oven", "stove"),
        "toaster" to listOf("toaster"),
        "sink" to listOf("sink"),
        "refrigerator" to listOf("refrigerator", "fridge"),
        "book" to listOf("book"),
        "clock" to listOf("clock"),
        "vase" to listOf("vase"),
        "scissors" to listOf("scissors"),
        "teddy bear" to listOf("teddy bear", "teddy", "toy"),
        "bird" to listOf("bird"),
        "cat" to listOf("cat", "kitten", "kitty"),
        "dog" to listOf("dog", "puppy", "doggy"),
        "horse" to listOf("horse"),
        "sports ball" to listOf("ball", "sports ball", "basketball", "soccer ball", "football")
    )

    fun isSupportedObject(rawQuery: String): Boolean {
        val normalized = normalizeQuery(rawQuery)
        if (unsupportedVocabulary.any { normalized == it || normalized.contains(it) }) {
            return false
        }
        return supportedObjectMap.any { (_, synonyms) ->
            synonyms.any { syn -> normalized == syn || normalized.contains(syn) }
        }
    }

    fun isExplicitlyUnsupported(rawQuery: String): Boolean {
        val normalized = normalizeQuery(rawQuery)
        return unsupportedVocabulary.any { synOrContains(it, normalized) }
    }

    private fun synOrContains(unsupported: String, query: String): Boolean {
        return query == unsupported || query.contains(unsupported)
    }

    fun executeFind(
        rawTarget: String,
        trackedObjects: List<TrackedDetection>
    ): String {
        val targetQuery = normalizeQuery(rawTarget)

        // 1. Check if the query is an unsupported class (e.g. door, stairs, curb)
        if (!isSupportedObject(targetQuery)) {
            return "I can't reliably search for that object yet."
        }

        // 2. Filter tracked objects matching the query
        val candidateMatches = trackedObjects.filter { obj ->
            matchesObject(obj, targetQuery) && obj.persistenceFrames >= 1 && obj.confidence >= 0.35f
        }

        val displayTargetName = getDisplayTargetName(targetQuery)

        if (candidateMatches.isEmpty()) {
            val article = getIndefiniteArticle(displayTargetName)
            return "I don't currently see $article$displayTargetName."
        }

        // 3. Sort candidate detections using the required hierarchy:
        // 1. Confidence
        // 2. Distance (closer first)
        // 3. Walking-path relevance (isDirectPath first)
        // 4. Detection persistence (higher first)
        val sortedMatches = candidateMatches.sortedWith(
            compareByDescending<TrackedDetection> { it.confidence }
                .thenBy { it.distanceMeters }
                .thenByDescending { it.isDirectPath }
                .thenByDescending { it.persistenceFrames }
        )

        // 4. Multiple matches formatting
        if (sortedMatches.size > 1) {
            val pluralName = getPluralName(displayTargetName)
            val first = sortedMatches[0]
            val second = sortedMatches[1]

            val firstZone = first.spatialZone.displayName
            val secondZone = second.spatialZone.displayName

            return if (firstZone == secondZone) {
                "There are ${sortedMatches.size} $pluralName. One is $firstZone at ${first.distanceMeters.toInt()} meters, and another is farther away."
            } else {
                "There are ${sortedMatches.size} $pluralName. One is $firstZone and one is $secondZone."
            }
        }

        // 5. Single match formatting
        val best = sortedMatches.first()
        val isLowConfidence = best.confidence < 0.60f
        val prefix = if (isLowConfidence) "Possible " else ""
        val capitalizedName = displayTargetName.replaceFirstChar { it.uppercase() }

        val distanceInt = best.distanceMeters.toInt()
        val distanceDesc = when {
            best.distanceMeters < 1.5f -> "very close"
            best.distanceMeters < 3.5f -> "approximately $distanceInt meters ahead"
            best.distanceMeters < 6.0f -> "nearby"
            else -> "farther away"
        }

        val spatialDesc = when (best.spatialZone) {
            SpatialZone.CENTER -> "directly ahead"
            SpatialZone.SLIGHT_LEFT -> "slightly left"
            SpatialZone.SLIGHT_RIGHT -> "slightly right"
            SpatialZone.LEFT -> "to your left"
            SpatialZone.RIGHT -> "to your right"
        }

        val speech = if (best.spatialZone == SpatialZone.CENTER) {
            "$prefix$capitalizedName $spatialDesc, $distanceDesc."
        } else {
            "$prefix$capitalizedName $spatialDesc, $distanceDesc."
        }

        return sanitizeFindOutput(speech)
    }

    private fun matchesObject(obj: TrackedDetection, targetQuery: String): Boolean {
        val labelLower = obj.label.lowercase()
        val categoryLower = obj.category.name.lowercase()

        // Check if query matches category directly
        if (targetQuery == "vehicle" && obj.category == ObjectCategory.VEHICLE) return true
        if (targetQuery == "car" && (labelLower.contains("car") || obj.category == ObjectCategory.VEHICLE)) return true
        if (targetQuery == "person" && (labelLower.contains("person") || obj.category == ObjectCategory.PERSON)) return true
        if (targetQuery == "chair" && (labelLower.contains("chair") || labelLower.contains("couch") || obj.category == ObjectCategory.CHAIR)) return true
        if (targetQuery == "table" && (labelLower.contains("table") || labelLower.contains("desk") || obj.category == ObjectCategory.TABLE)) return true

        // Check against supported synonyms
        for ((canonical, synonyms) in supportedObjectMap) {
            val queryMatchesCanonical = synonyms.any { targetQuery == it || targetQuery.contains(it) }
            if (queryMatchesCanonical) {
                if (labelLower.contains(canonical) || labelLower == canonical) return true
                if (synonyms.any { labelLower.contains(it) }) return true
            }
        }

        return labelLower.contains(targetQuery) || targetQuery.contains(labelLower)
    }

    private fun normalizeQuery(query: String): String {
        return query.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\b(the|a|an|my|this|that)\\b"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun getDisplayTargetName(targetQuery: String): String {
        for ((canonical, synonyms) in supportedObjectMap) {
            if (synonyms.any { targetQuery == it || targetQuery.contains(it) }) {
                return canonical
            }
        }
        return targetQuery
    }

    private fun getIndefiniteArticle(word: String): String {
        val firstChar = word.trim().lowercase().firstOrNull() ?: return "a "
        return if (firstChar in listOf('a', 'e', 'i', 'o', 'u')) "an " else "a "
    }

    private fun getPluralName(word: String): String {
        val lower = word.lowercase().trim()
        return when {
            lower == "person" -> "people"
            lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("s") || lower.endsWith("x") -> "${lower}es"
            else -> "${lower}s"
        }
    }

    private fun sanitizeFindOutput(text: String): String {
        // Enforce safety constraints: strip any false safety assertions
        return text
            .replace(Regex("(?i)\\bpath is clear\\b"), "")
            .replace(Regex("(?i)\\bobject is safe\\b"), "")
            .replace(Regex("(?i)\\byou can walk there\\b"), "")
            .replace(Regex("(?i)\\bsafe to walk\\b"), "")
            .trim()
    }
}
