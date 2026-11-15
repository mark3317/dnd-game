package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.markn.dnd.network.RoomPresentation
import ru.markn.dnd.network.RoomSpeechKind

/** An authority-published start is also the bounded readiness fallback/reconnect release. */
internal fun endingPlaybackAllowed(presentation: RoomPresentation?, visualsPending: Boolean): Boolean = !visualsPending ||
    presentation?.cues?.any { it.kind == RoomSpeechKind.ENDING && it.startsAt != null } == true

/** Local presentation clock only: never changes the game state, dice or story outcome. */
internal class LocalEndingPlayback {
    var startedAt by mutableStateOf<Long?>(null)
        private set

    fun update(endingAt: Long, visualsPending: Boolean, now: Long) {
        if (endingAt <= 0) startedAt = null
        else if (startedAt == null && !visualsPending && now >= endingAt) startedAt = now
    }

    fun finished(now: Long, duration: Long, voiceBusy: Boolean, visualsPending: Boolean): Boolean =
        startedAt?.let { now >= it + duration } == true && !voiceBusy && !visualsPending
}
