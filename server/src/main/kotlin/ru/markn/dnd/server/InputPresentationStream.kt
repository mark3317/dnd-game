package ru.markn.dnd.server

import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.InputUtterance
import ru.markn.dnd.adventure.validateInputPresentation

/** Locate a complete top-level array regardless of JSON field order; kotlinx.serialization validates its contents.
 * Partial strings, escaped quotes and brackets inside text must never trigger speech. */
internal fun inputPresentationPrefix(text: String): List<InputUtterance>? {
    var objectDepth = 0
    var inString = false
    var escape = false
    var start = -1
    for (index in text.indices) {
        val char = text[index]
        if (inString) {
            if (escape) escape = false
            else if (char == '\\') escape = true
            else if (char == '"') inString = false
        } else {
            if (char == '"') {
                if (objectDepth == 1) {
                    val match = Regex("^\"inputPresentation\"\\s*:\\s*\\[").find(text.substring(index))
                    if (match != null) { start = index + match.range.last; break }
                }
                inString = true
            } else if (char == '{' || char == '[') objectDepth++
            else if (char == '}' || char == ']') objectDepth--
        }
    }
    if (start < 0) return null
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
