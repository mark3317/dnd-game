package ru.markn.dnd.adventure.ui

import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer

/** Recovery is enabled only for backends whose seek/start ordering has been inspected. */
internal expect val roomAudioRecoverySeekSupported: Boolean
internal expect fun SoundscapePlayer.startRoomAudio(offsetMillis: Long)
