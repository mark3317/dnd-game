package ru.markn.dnd.adventure.ui

import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer

// Normal playback works; recovery uses text until seek semantics are verified on device.
internal actual val roomAudioRecoverySeekSupported = false

internal actual fun SoundscapePlayer.startRoomAudio(offsetMillis: Long) {
    require(offsetMillis == 0L)
    play()
}
