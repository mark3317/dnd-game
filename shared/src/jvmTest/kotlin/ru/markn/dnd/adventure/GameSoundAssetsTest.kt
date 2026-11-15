package ru.markn.dnd.adventure

import java.net.URI
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import ru.markn.dnd.adventure.ui.GameSound
import ru.markn.dnd.generated.resources.Res

class GameSoundAssetsTest {
    @Test fun shippedEffectsAreLicensedMeasuredMonoPcmWithUnchangedHashes() = runBlocking {
        val assets = Json.parseToJsonElement(Res.readBytes("files/effects/catalog.json").decodeToString()).jsonArray
        assertEquals(GameSound.entries.map { it.assetId }.toSet(), assets.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        for (record in assets.map { it.jsonObject }) {
            val file = record.getValue("file").jsonPrimitive.content
            assertTrue(file.matches(Regex("[a-z0-9_]+\\.wav")))
            assertEquals("CC0-1.0", record.getValue("license").jsonPrimitive.content)
            assertTrue(record.getValue("sourceSha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            val bytes = Res.readBytes("files/effects/$file")
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(record.getValue("sha256").jsonPrimitive.content, hash, file)
            AudioSystem.getAudioInputStream(URI(Res.getUri("files/effects/$file")).toURL()).use { audio ->
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, audio.format.encoding)
                assertEquals(1, audio.format.channels)
                assertEquals(16, audio.format.sampleSizeInBits)
                assertFalse(audio.format.isBigEndian)
                assertTrue(audio.format.sampleRate in listOf(44100f, 48000f))
                val duration = (audio.frameLength * 1000.0 / audio.format.frameRate).roundToLong()
                assertEquals(record.getValue("duration_ms").jsonPrimitive.long, duration, file)
                val pcm = audio.readBytes()
                var peak = 0
                for (i in pcm.indices step 2) {
                    val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, abs(sample))
                }
                assertTrue(peak in 1..11_000, "$file must contain audio below the -10dBFS peak ceiling")
            }
        }
        for (license in listOf("kenney-rpg.txt", "kenney-impact.txt", "jaggedstone-source.md")) {
            assertTrue(Res.readBytes("files/effects/licenses/$license").decodeToString().contains("CC0", ignoreCase = true))
        }
    }
}
