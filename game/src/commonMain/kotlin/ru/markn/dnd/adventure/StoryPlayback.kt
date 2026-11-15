package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable
import kotlin.time.Clock

fun storyNowMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val SUBTITLE_CHARACTER_MILLIS = 35L
fun subtitleDuration(text: String): Long = maxOf(text.length * SUBTITLE_CHARACTER_MILLIS + 1600L,
    preparedSpeechDurations[text] ?: 0L)

/** Keep every word, while limiting each subtitle to a readable screenful. */
fun subtitleSegments(text: String): List<String> {
    val result = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > 180) {
        var cut = rest.lastIndexOf(' ', 180).takeIf { it > 0 } ?: 180
        if (rest[cut - 1].isHighSurrogate()) cut--
        result += rest.take(cut)
        rest = rest.drop(cut).trimStart()
    }
    if (rest.isNotEmpty()) result += rest
    return result
}

@Serializable
data class TimedSpeech(val entry: JournalEntry, val startsAt: Long, val endsAt: Long)

/** Keep authored recordings intact; synthesize new text in sentence-sized buffers. */
fun speechSegments(text: String): List<String> = subtitleSegments(text).flatMap { segment ->
    if (segment in preparedSpeechDurations) listOf(segment)
    else {
        val boundaries = Regex("[.!?…][»\"”)]*\\s+").findAll(segment)
            .map { it.range.last + 1 }.toList()
        val result = mutableListOf<String>()
        var start = 0
        for (end in boundaries) {
            if (end - start >= 40) {
                result += segment.substring(start, end).trim()
                start = end
            }
        }
        segment.substring(start).trim().takeIf { it.isNotEmpty() }?.let { result += it }
        result
    }
}

@Serializable
data class StoryPlayback(
    val openingAt: Long = 0,
    val openingEndsAt: Long = 0,
    val endingAt: Long = 0,
    val endingEndsAt: Long = 0,
    val speeches: List<TimedSpeech> = emptyList(),
    val actorId: String? = null,
    // A preview is an accepted intention, never a confirmed action or journal mutation.
    val previewEntryId: Long? = null,
    val previewEntryIds: List<Long> = emptyList(),
) {
    val busyUntil: Long get() = maxOf(openingEndsAt, endingEndsAt, speeches.lastOrNull()?.endsAt ?: 0)
}

/** Publish classified hero/narrator speech while the rest of the model response is still arriving. */
fun AdventureState.previewInputPresentation(parts: List<InputUtterance>, now: Long): AdventureState {
    val entries = inputEntries(parts)
    var cursor = maxOf(now + 350, playback.busyUntil)
    val speeches = playback.speeches.filter { it.endsAt > now } + entries.flatMap { entry -> speechSegments(entry.text).map { part ->
        val start = cursor
        cursor += subtitleDuration(part)
        TimedSpeech(entry.copy(text = part), start, cursor)
    } }
    return copy(playback = playback.copy(speeches = speeches, actorId = activePlayer.id,
        previewEntryId = (journal.lastOrNull()?.id ?: 0) + 1, previewEntryIds = entries.map { it.id }))
}

/** Scheduled once by the authority, retained on reconnect; client speed never advances play. */
fun AdventureState.schedulePlayback(previous: AdventureState?, now: Long): AdventureState {
    val fresh = previous == null || previous.sessionId != sessionId
    if (fresh) {
        val start = now + 350
        return copy(playback = StoryPlayback(openingAt = start,
            openingEndsAt = start + openingShots().sumOf { subtitleDuration(it.text) }))
    }
    // Engine copies retain the timeline; a preview may have explicitly extended it.
    val old = playback
    var cursor = maxOf(now + 350, old.busyUntil)
    val added = journal.filter { it.id > (previous.journal.lastOrNull()?.id ?: 0) && it.isSpoken }
    val speeches = old.speeches.filter { it.endsAt > now }.toMutableList()
    // The authored outcome is already narrated by the ending shots.
    for (entry in added.filterNot {
        (previous.ending == null && ending != null && it.kind == EntryKind.NARRATOR) ||
            it.id in old.previewEntryIds
    }.flatMap { entry -> speechSegments(entry.text).map { entry.copy(text = it) } }) {
        val end = cursor + subtitleDuration(entry.text)
        speeches += TimedSpeech(entry, cursor, end)
        cursor = end
    }
    val endingStart = if (previous.ending == null && ending != null) cursor else old.endingAt
    return copy(playback = old.copy(speeches = speeches,
        previewEntryId = old.previewEntryId?.takeUnless { id -> journal.any { it.id == id } },
        previewEntryIds = old.previewEntryIds.takeUnless { ids -> added.any { it.id in ids } }.orEmpty(),
        actorId = if (old.busyUntil > now) old.actorId ?: previous.activePlayer.id else previous.activePlayer.id,
        endingAt = endingStart,
        endingEndsAt = if (previous.ending == null && ending != null)
            endingStart + endingShots().sumOf { subtitleDuration(it.text) } else old.endingEndsAt))
}
