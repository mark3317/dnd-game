package ru.markn.dnd.adventure.ui

import java.net.URL
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import ru.markn.dnd.generated.resources.Res

class BundledMusicTest {
    @Test fun allBundledTracksAreReadableByTheDesktopMp3Decoder() {
        for (file in MusicCue.entries.map { it.file }.distinct()) {
            AudioSystem.getAudioInputStream(URL(Res.getUri("files/music/$file.mp3")).openStream().buffered()).use { compressed ->
                val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, compressed.format.sampleRate,
                    16, compressed.format.channels, compressed.format.channels * 2, compressed.format.sampleRate, false)
                AudioSystem.getAudioInputStream(pcm, compressed).use { decoded ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    var audible = false
                    while (true) {
                        val count = decoded.read(buffer)
                        if (count < 0) break
                        total += count
                        if (!audible) for (index in 0 until count) if (buffer[index] != 0.toByte()) { audible = true; break }
                    }
                    assertTrue(total > 44100, "$file must decode into PCM samples")
                    assertTrue(audible, "$file must contain audio after its initial silence")
                }
            }
        }
    }
}
