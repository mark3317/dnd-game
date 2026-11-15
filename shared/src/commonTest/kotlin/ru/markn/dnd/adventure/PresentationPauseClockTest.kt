package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.PresentationPauseClock

class PresentationPauseClockTest {
    @Test fun pausedTimeDoesNotAdvanceAndRepeatedSnapshotsDoNotDoubleShift() {
        var wall = 1000L
        val clock = PresentationPauseClock { wall }
        wall = 1300
        clock.setPaused(true)
        wall = 7300
        clock.setPaused(true)
        assertEquals(1300L, clock.nowMillis())
        clock.setPaused(false)
        assertEquals(1300L, clock.nowMillis())
        clock.setPaused(false)
        wall = 7600
        assertEquals(1600L, clock.nowMillis())
        clock.setPaused(true)
        wall = 9600
        clock.setPaused(false)
        assertEquals(1600L, clock.nowMillis())
    }
}
