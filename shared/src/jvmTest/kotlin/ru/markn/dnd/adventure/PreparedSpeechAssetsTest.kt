package ru.markn.dnd.adventure

import java.net.URI
import javax.sound.sampled.AudioSystem
import kotlin.math.roundToLong
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ru.markn.dnd.generated.resources.Res

class PreparedSpeechAssetsTest {
    @Test fun roomAuthorityUsesTheSameFilesAndActualDurationsAsBundledSpeech() = runBlocking {
        val catalog = Json.decodeFromString<List<PreparedSpeech>>(
            Res.readBytes("files/speech/catalog.json").decodeToString())
        assertTrue(catalog.isNotEmpty())
        assertEquals(catalog.size, catalog.map { it.text to it.voice }.distinct().size)
        for (recording in catalog) {
            assertEquals(recording, preparedSpeechAsset(recording.text, recording.voice))
            assertTrue(recording.file.matches(Regex("[a-f0-9]{64}\\.wav")))
            AudioSystem.getAudioInputStream(URI(Res.getUri("files/speech/${recording.file}")).toURL()).use { audio ->
                assertEquals(1, audio.format.channels)
                assertEquals(16, audio.format.sampleSizeInBits)
                val actualMillis = (audio.frameLength * 1000.0 / audio.format.frameRate).roundToLong()
                assertEquals(recording.duration_ms, actualMillis, recording.file)
            }
        }
        assertNull(preparedSpeechAsset(catalog.first().text, "unknown-voice"))
    }
}
