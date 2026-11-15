package ru.markn.dnd.adventure.ui

import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer
import kotlin.time.Duration.Companion.milliseconds

internal actual val roomAudioRecoverySeekSupported = true

internal actual fun SoundscapePlayer.startRoomAudio(offsetMillis: Long) {
    if (offsetMillis > 0) seekTo(offsetMillis.milliseconds)
    play()
}
