package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Wait for both the subtitle timeline and actual voice playback before entering combat. */
internal class CombatEntrance {
    var visible by mutableStateOf(false)
        private set

    fun update(hasCombat: Boolean, narrationBusy: Boolean) {
        // Later combat narration must not send the player back to the conversation scene.
        visible = hasCombat && (visible || !narrationBusy)
    }
}
