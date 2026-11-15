package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.SpeechRequest
import ru.markn.dnd.adventure.SpeechResult

fun interface RoomSpeechPreparer {
    suspend fun prepare(text: String, voice: String): RoomSpeechAudio?
}

/** Room authority talks to the same local GPU worker; clients only fetch the resulting file. */
internal class LocalRoomSpeechPreparer(private val http: HttpClient, private val worker: String = "http://127.0.0.1:8091") : RoomSpeechPreparer {
    private val slots = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun prepare(text: String, voice: String): RoomSpeechAudio? = withTimeoutOrNull(15_000) {
        slots.withPermit {
            try {
                var result = json.decodeFromString<SpeechResult>(http.post("$worker/speech/prepare") {
                    contentType(ContentType.Application.Json); setBody(json.encodeToString(SpeechRequest(text, voice)))
                }.bodyAsText())
                while (result.status in setOf("queued", "generating")) {
                    if (!result.id.matches(Regex("[a-f0-9]{64}"))) return@withPermit null
                    delay(250)
                    result = json.decodeFromString(http.get("$worker/speech/status/${result.id}").bodyAsText())
                }
                if (result.status == "ready" && result.id.matches(Regex("[a-f0-9]{64}")) && result.duration_ms in 1..120_000)
                    RoomSpeechAudio(result.id, result.duration_ms) else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}
