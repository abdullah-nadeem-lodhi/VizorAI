package com.example.domain.engine

/**
 * Natural voice command parser (Iteration 9).
 *
 * Parses spoken voice input transcripts to identify commands including:
 * - "Describe surroundings"
 * - "Find [object]" / "Where is the [object]" / "Locate [object]" / "Can you find [object]"
 * - "Suggest path" / "Safe path" / "Which way" / "Where to walk" / "Path guidance"
 * - "Stop" / "Start" / "Mute" / "Unmute"
 */
enum class VoiceCommand {
    DESCRIBE_SURROUNDINGS,
    FIND_OBJECT,
    SUGGEST_PATH,
    STOP_GUIDANCE,
    START_GUIDANCE,
    MUTE_AUDIO,
    UNMUTE_AUDIO,
    UNKNOWN
}

data class ParsedVoiceResult(
    val command: VoiceCommand,
    val targetObject: String? = null
)

object VoiceCommandParser {

    private val findPrefixRegexes = listOf(
        Regex("^(?:can you )?(?:please )?(?:find|locate|search for|look for)(?: me)? (?:the |a |an |my )?(.+)$"),
        Regex("^(?:where is|where are|wheres|where's)(?: the| a| an| my)? (.+)$"),
        Regex("^(?:show me|point me to)(?: the| a| an| my)? (.+)$")
    )

    fun parse(rawTranscript: String?): VoiceCommand {
        return parseCommand(rawTranscript).command
    }

    fun parseCommand(rawTranscript: String?): ParsedVoiceResult {
        if (rawTranscript.isNullOrBlank()) {
            return ParsedVoiceResult(VoiceCommand.UNKNOWN)
        }

        val normalized = rawTranscript.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")

        // 1. Check Describe Surroundings
        if (normalized.contains("describe surroundings") ||
            normalized.contains("describe surrounding") ||
            normalized.contains("describe the surroundings") ||
            normalized.contains("describe scene") ||
            normalized.contains("describe the scene") ||
            normalized.contains("what is around me") ||
            normalized.contains("whats around me") ||
            normalized.contains("what is around") ||
            normalized.contains("tell me surroundings") ||
            normalized.contains("tell me whats around") ||
            normalized == "surroundings" ||
            normalized == "describe"
        ) {
            return ParsedVoiceResult(VoiceCommand.DESCRIBE_SURROUNDINGS)
        }

        // 2. Check Suggest Path / Directional Guidance
        if (normalized.contains("suggest path") ||
            normalized.contains("safe path") ||
            normalized.contains("which way") ||
            normalized.contains("where to walk") ||
            normalized.contains("where can i go") ||
            normalized.contains("which direction") ||
            normalized.contains("path guidance") ||
            normalized.contains("guide path") ||
            normalized == "path" ||
            normalized == "way"
        ) {
            return ParsedVoiceResult(VoiceCommand.SUGGEST_PATH)
        }

        // 3. Check Stop Guidance
        if (normalized == "stop" ||
            normalized == "stop guidance" ||
            normalized == "stop camera" ||
            normalized == "cancel"
        ) {
            return ParsedVoiceResult(VoiceCommand.STOP_GUIDANCE)
        }

        // 4. Check Start Guidance
        if (normalized == "start" ||
            normalized == "start guidance" ||
            normalized == "start camera"
        ) {
            return ParsedVoiceResult(VoiceCommand.START_GUIDANCE)
        }

        // 5. Check Mute / Unmute
        if (normalized == "mute" || normalized == "silence" || normalized == "quiet") {
            return ParsedVoiceResult(VoiceCommand.MUTE_AUDIO)
        }
        if (normalized == "unmute" || normalized == "turn on audio") {
            return ParsedVoiceResult(VoiceCommand.UNMUTE_AUDIO)
        }

        // 6. Check Find Object variations
        for (pattern in findPrefixRegexes) {
            val match = pattern.find(normalized)
            if (match != null) {
                val rawTarget = match.groupValues[1].trim()
                // Ensure target is not empty and not an unrelated phrase
                if (rawTarget.isNotBlank() && rawTarget != "surroundings" && rawTarget != "scene" && rawTarget != "path") {
                    val cleanTarget = rawTarget
                        .replace(Regex("^(?:the|a|an|my|any)\\s+"), "")
                        .replace(Regex("\\s+(?:please|now)$"), "")
                        .trim()
                    if (cleanTarget.isNotEmpty()) {
                        return ParsedVoiceResult(VoiceCommand.FIND_OBJECT, cleanTarget)
                    }
                }
            }
        }

        return ParsedVoiceResult(VoiceCommand.UNKNOWN)
    }
}
