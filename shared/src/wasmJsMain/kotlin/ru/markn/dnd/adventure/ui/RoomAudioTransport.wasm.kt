package ru.markn.dnd.adventure.ui

import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer

// Rejoining mid-file is text-only until browser seek and autoplay are verified together.
internal actual val roomAudioRecoverySeekSupported = false

internal actual fun SoundscapePlayer.startRoomAudio(offsetMillis: Long) {
    require(offsetMillis == 0L)
    play()
}
