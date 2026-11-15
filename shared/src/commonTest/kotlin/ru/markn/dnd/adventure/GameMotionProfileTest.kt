package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.GameMotionProfile

class GameMotionProfileTest {
    @Test fun reducedProfileRemovesDecorativeTimeButPreservesReadingWindowsAndCameraPreference() {
        val normal = GameMotionProfile(false)
        val reduced = GameMotionProfile(true)
        assertTrue(normal.cameraActive(true))
        assertFalse(normal.cameraActive(false))
        assertFalse(reduced.cameraActive(true))
        assertFalse(reduced.cameraActive(false))
        assertEquals(0, reduced.transitionMillis(400))
        assertEquals(0, reduced.diceWindupMillis)
        assertEquals(0, reduced.interactionWindupMillis)
        assertEquals(1800L, reduced.diceResultMillis)
        assertEquals(normal.diceResultMillis, reduced.diceResultMillis)
        assertEquals(2400L, reduced.interactionResultMillis)
        assertEquals(normal.interactionResultMillis, reduced.interactionResultMillis)
    }
}
