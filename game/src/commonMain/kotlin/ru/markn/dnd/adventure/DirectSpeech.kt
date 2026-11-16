package ru.markn.dnd.adventure

/** Explicit quotation of the whole input; never infer speech from natural-language wording. */
fun directSpeechPresentation(input: String): List<InputUtterance>? {
    val text = input.trim()
    if (text.length !in 3..AdventureEngine.MAX_ACTION_LENGTH) return null
    val open = text.first()
    val close = when (open) { '«' -> '»'; '"' -> '"'; else -> return null }
    if (text.last() != close) return null
    val body = text.substring(1, text.lastIndex).trim()
    if (body.isBlank()) return null
    if (open == close) {
        if (close in body) return null
    } else {
        var depth = 1
        for (char in body) {
            if (char == open) depth++
            if (char == close) depth--
            if (depth == 0) return null
        }
        if (depth != 1) return null
    }
    return listOf(InputUtterance(InputUtteranceKind.SPEECH, body))
}
