package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.network.*

class EndingPresentationGateTest {
    @Test fun mutedLocalEndingGetsItsFullIntervalAfterAnOverdueVisualQueue() {
        val clock = LocalEndingPlayback()
        // Old endingAt/endingEndsAt were 1000/3500, but the last battle insertion is still running.
        clock.update(endingAt = 1000, visualsPending = true, now = 5000)
        assertNull(clock.startedAt)
        assertFalse(clock.finished(5000, 2500, voiceBusy = false, visualsPending = true))
        clock.update(endingAt = 1000, visualsPending = false, now = 6000)
        assertEquals(6000L, clock.startedAt)
        assertFalse(clock.finished(6000, 2500, voiceBusy = false, visualsPending = false))
        assertFalse(clock.finished(8499, 2500, voiceBusy = false, visualsPending = false))
        assertTrue(clock.finished(8500, 2500, voiceBusy = false, visualsPending = false))
        assertFalse(clock.finished(9000, 2500, voiceBusy = true, visualsPending = false))
        assertFalse(clock.finished(9000, 2500, voiceBusy = false, visualsPending = true))
        clock.update(1000, false, 9000)
        assertEquals(6000L, clock.startedAt, "A later frame must not restart the cinematic clock")
    }

    @Test fun freshFinalVisualsHoldCinemaUntilDrainOrAnAuthorityPublishedBoundedStart() {
        val ending = RoomSpeechCue("ending", RoomSpeechKind.ENDING, JournalEntry(1, EntryKind.NARRATOR, "Итог"), "narrator")
        val room = RoomPresentation(1, "batch", cues = listOf(ending))
        assertFalse(endingPlaybackAllowed(room, visualsPending = true))
        assertTrue(endingPlaybackAllowed(room, visualsPending = false))
        assertTrue(endingPlaybackAllowed(room.copy(cues = listOf(ending.copy(startsAt = 30_350))), visualsPending = true))
        assertFalse(endingPlaybackAllowed(room.copy(cues = listOf(ending.copy(kind = RoomSpeechKind.DIALOGUE, startsAt = 1000))), visualsPending = true))
    }
}
