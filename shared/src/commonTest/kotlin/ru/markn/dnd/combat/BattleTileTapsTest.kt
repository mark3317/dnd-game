package ru.markn.dnd.combat

import kotlin.test.*

class BattleTileTapsTest {
    @Test fun firstClickSelectsAndSecondClickConfirmsOnlyOnce() {
        val taps = BattleTileTaps(300)
        val cell = Tile(1, 2)
        assertFalse(taps.release(cell, 1000))
        assertTrue(taps.release(cell, 1100))
        assertFalse(taps.release(cell, 1200))
    }

    @Test fun differentCellTimeoutAndCancelledGestureDoNotConfirm() {
        val taps = BattleTileTaps(300)
        assertFalse(taps.release(Tile(1, 2), 1000))
        assertFalse(taps.release(Tile(2, 2), 1100))
        assertFalse(taps.release(Tile(2, 2), 1500))
        taps.reset()
        assertFalse(taps.release(Tile(2, 2), 1600))
        assertTrue(taps.release(Tile(2, 2), 1700))
    }
}
