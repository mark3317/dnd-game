package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.network.GameWireJson

class StoryPlaybackTest {
    private val engine = AdventureEngine()

    @Test fun previewIsImmediateAndIsNotRepeatedWhenTheModelReturnsLate() {
        val start = engine.newGame().schedulePlayback(null, 1000)
        val now = start.playback.busyUntil
        val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин осматривается."))
        val preview = start.previewInputPresentation(parts, now).schedulePlayback(start, now)
        assertEquals(start.journal, preview.journal)
        assertEquals(start.turnId, preview.turnId)
        assertEquals(start.players, preview.players)
        assertEquals("Рин осматривается.", preview.playback.speeches.single().entry.text)
        assertEquals(now + 350, preview.playback.speeches.single().startsAt)
        val late = preview.playback.busyUntil + 5000
        val result = engine.submit(preview, preview.activePlayer.id, preview.turnId, "Осмотреться")
            .schedulePlayback(preview, late)
        assertTrue(result.playback.speeches.none { it.entry.kind == EntryKind.PLAYER })
        assertTrue(result.playback.speeches.any { it.entry.kind == EntryKind.NARRATOR })
        assertEquals(1, result.journal.count { it.kind == EntryKind.PLAYER })
        assertNull(result.playback.previewEntryId)
    }

    @Test fun fastResponseWaitsBehindThePreviewWithoutRepeatingIt() {
        val start = engine.newGame().schedulePlayback(null, 1000)
        val now = start.playback.busyUntil
        val preview = start.previewInputPresentation(listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин осматривается.")), now).schedulePlayback(start, now)
        val result = engine.submit(preview, preview.activePlayer.id, preview.turnId, "Осмотреться")
            .schedulePlayback(preview, now + 500)
        assertEquals(1, result.playback.speeches.count { it.entry.text == "Рин осматривается." })
        result.playback.speeches.zipWithNext().forEach { (a, b) -> assertEquals(a.endsAt, b.startsAt) }
    }

    @Test fun dynamicSpeechUsesSentencesAndPreservesPreparedRecordings() {
        val text = "Это достаточно длинная первая фраза для отдельной реплики. А это следующая фраза с ответом."
        val parts = speechSegments(text)
        assertEquals(2, parts.size)
        assertEquals(text, parts.joinToString(" "))
        preparedSpeechDurations.keys.forEach { assertEquals(listOf(it), speechSegments(it)) }
        val long = List(100) { "Слово$it" }.joinToString(" ") + " 🐉"
        assertEquals(long, speechSegments(long).joinToString(" "))
        assertTrue(speechSegments(long).all { it.length <= 180 })
    }

    @Test fun speechIsQueuedInOrderAndRetainsTheActingHeroUntilItFinishes() {
        val start = engine.newGame().schedulePlayback(null, 1000)
        val now = start.playback.busyUntil
        val result = engine.submit(start, start.activePlayer.id, start.turnId, "Осмотреться")
        val next = result.schedulePlayback(start, now)
        assertEquals(start.activePlayer.id, next.playback.actorId)
        assertEquals(EntryKind.NARRATOR, next.playback.speeches.first().entry.kind)
        assertEquals(EntryKind.NARRATOR, next.playback.speeches.last().entry.kind)
        next.playback.speeches.zipWithNext().forEach { (a, b) -> assertEquals(a.endsAt, b.startsAt) }
        assertTrue(next.playback.busyUntil > now)
        assertEquals(next, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(next)))
    }

    @Test fun updatesAndReconnectDoNotRestartOpeningOrRepeatOldSpeech() {
        val start = engine.newGame().schedulePlayback(null, 1000)
        val updated = start.schedulePlayback(start, 2000)
        assertEquals(start.playback.openingAt, updated.playback.openingAt)
        assertEquals(start.playback.openingEndsAt, updated.playback.openingEndsAt)
        assertTrue(updated.playback.speeches.isEmpty())
        val restarted = engine.newGame(start.sessionId + 1).schedulePlayback(start, 90_000)
        assertEquals(90_350, restarted.playback.openingAt)
    }

    @Test fun longSpeechIsSplitWithoutDroppingWordsOrBreakingUnicode() {
        val text = List(100) { "Слово$it" }.joinToString(" ") + " 🐉"
        val segments = subtitleSegments(text)
        assertTrue(segments.all { it.length <= 180 })
        assertEquals(text, segments.joinToString(" "))
    }

    @Test fun endingStartsAfterQueuedSpeechAndDoesNotDuplicateAuthoredNarration() {
        val start = engine.newGame(scenarioId = ArchiveDemo.ID).schedulePlayback(null, 1000)
        val ending = start.copy(ending = ArchiveDemo.DEFEAT, journal = start.journal +
            JournalEntry(100, EntryKind.NARRATOR, start.scenario.endings.getValue(ArchiveDemo.DEFEAT).description))
            .schedulePlayback(start, start.playback.busyUntil)
        assertTrue(ending.playback.speeches.isEmpty())
        assertTrue(ending.playback.endingAt >= start.playback.busyUntil)
        assertEquals(ending.endingShots().sumOf { subtitleDuration(it.text) },
            ending.playback.endingEndsAt - ending.playback.endingAt)
    }
}
