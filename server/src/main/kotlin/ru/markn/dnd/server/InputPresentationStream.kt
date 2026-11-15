package ru.markn.dnd.server

import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.InputUtterance
import ru.markn.dnd.adventure.validateInputPresentation

/** Locate only a complete first-field array; kotlinx.serialization validates its contents.
 * Partial strings, escaped quotes and brackets inside text must never trigger speech. */
internal fun inputPresentationPrefix(text: String): List<InputUtterance>? {
    val prefix = Regex("^\\s*\\{\\s*\"inputPresentation\"\\s*:\\s*\\[").find(text) ?: return null
    val start = prefix.range.last
    var quoted = false
    var escaped = false
    var depth = 0
    for (index in start until text.length) {
        val char = text[index]
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return try {
                    Json.decodeFromString<List<InputUtterance>>(text.substring(start, index + 1))
                        .also(::validateInputPresentation)
                } catch (_: Exception) { throw AiFailure("input_presentation_invalid") }
            }
        }
    }
    return null
}
