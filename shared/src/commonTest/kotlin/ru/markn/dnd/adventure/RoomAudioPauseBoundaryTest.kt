package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.roomAudioFinished

class RoomAudioPauseBoundaryTest {
    @Test fun endedBeforePauseIsCompletedOnResumeInsteadOfStartingFileAgain() {
        assertTrue(roomAudioFinished(true, true, 1200, 1200, ended = true))
        // Web/native pause may replace Ended state; confirmed progress at full duration still proves EOF.
        assertTrue(roomAudioFinished(true, true, 1200, 1200, ended = false))
        assertFalse(roomAudioFinished(true, true, 1199, 1200, ended = false))
    }

    @Test fun unstartedOrStalePositionCannotFinishCurrentCue() {
        assertFalse(roomAudioFinished(false, false, 1200, 1200, ended = true))
        assertFalse(roomAudioFinished(true, false, 1200, 1200, ended = false))
        assertFalse(roomAudioFinished(true, true, 0, 0, ended = false))
    }
}
