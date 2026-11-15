package ru.markn.dnd.adventure

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SpeechRequest(val text: String, val voice: String = "narrator")

@Serializable
data class SpeechResult(val id: String, val status: String, val duration_ms: Long = 0, val generation_ms: Long = 0)

@Serializable
data class PreparedSpeech(val text: String, val voice: String, val file: String = "", val duration_ms: Long = 0)

fun AdventureState.speechVoice(entry: JournalEntry): String = when (entry.kind) {
    EntryKind.NPC -> when (entry.speakerNpcId) { "ARCHIVIST" -> "archivist"; "COURIER" -> "courier"; else -> "narrator" }
    EntryKind.PLAYER -> when (entry.actorName ?: players.firstOrNull { it.id == entry.playerId }?.hero?.name) {
        "Бран" -> "bran"; "Рин" -> "rin"; "Мира" -> "mira"; "Лада" -> "lada"
        else -> when (players.firstOrNull { it.id == entry.playerId }?.hero?.profile?.characterClass) {
            CharacterClass.ROGUE -> "rin"
            CharacterClass.MAGE -> "mira"
            CharacterClass.BARD -> "lada"
            else -> "bran"
        }
    }
    else -> "narrator"
}

/** Fixed origin chosen by the game connection; providers and model names never enter clients. */
class SpeechClient(private val baseUrl: String) {
    private val http = HttpClient { expectSuccess = true }
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun prepare(request: SpeechRequest): SpeechResult = json.decodeFromString(http.post("$baseUrl/speech/prepare") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(request))
    }.bodyAsText())
    suspend fun status(id: String): SpeechResult = json.decodeFromString(http.get("$baseUrl/speech/status/$id").bodyAsText())
    fun audioUrl(id: String): String = "$baseUrl/speech/audio/$id.wav"
    fun close() = http.close()
}

fun speechServerOrigin(address: String): String {
    val url = Url(address.trim())
    require(url.protocol.name in listOf("ws", "wss", "http", "https"))
    return URLBuilder(url).apply {
        protocol = if (url.protocol.name in listOf("wss", "https")) URLProtocol.HTTPS else URLProtocol.HTTP
        encodedPath = ""; parameters.clear(); fragment = ""
    }.buildString().trimEnd('/')
}
