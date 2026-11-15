package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.CombatEntrance

class CombatEntranceTest {
    @Test fun waitsForTimelineVoiceAndPendingNarration() {
        val entrance = CombatEntrance()
        entrance.update(hasCombat = false, narrationBusy = false)
        entrance.update(hasCombat = true, narrationBusy = true)
        assertFalse(entrance.visible)
        // The server timeline can finish before buffered synthesis/playback finishes.
        entrance.update(hasCombat = true, narrationBusy = true)
        assertFalse(entrance.visible)
        entrance.update(hasCombat = true, narrationBusy = false)
        assertTrue(entrance.visible)
        // Subsequent combat commentary keeps the battle on screen.
        entrance.update(hasCombat = true, narrationBusy = true)
        assertTrue(entrance.visible)
    }

    @Test fun reconnectAndSubsequentEncountersRespectCurrentPlayback() {
        val entrance = CombatEntrance()
        entrance.update(hasCombat = true, narrationBusy = true)
        assertFalse(entrance.visible)
        entrance.update(hasCombat = true, narrationBusy = false)
        assertTrue(entrance.visible)
        entrance.update(hasCombat = false, narrationBusy = false)
        assertFalse(entrance.visible)
        entrance.update(hasCombat = true, narrationBusy = true)
        assertFalse(entrance.visible)
    }
}
