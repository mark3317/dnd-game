package ru.markn.dnd.server

import io.ktor.http.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import ru.markn.dnd.adventure.*

class InputPresentationStreamTest {
    @Test fun completeArrayIsParsedAcrossEveryBoundaryIncludingEscapes() {
        val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин смотрит на знак [выход]."),
            InputUtterance(InputUtteranceKind.SPEECH, "Я говорю: \"Привет\".\\"))
        val array = Json.encodeToString(parts)
        val prefix = "{\"action\":\"TALK\",\"inputPresentation\":$array"
        for (cut in 0 until prefix.length) assertNull(inputPresentationPrefix(prefix.take(cut)))
        assertEquals(parts, inputPresentationPrefix(prefix))
        assertEquals(parts, inputPresentationPrefix("  $prefix,\"action\":null"))
        assertNull(inputPresentationPrefix("{\"narration\":\"inputPresentation: []\"}"))
        assertNull(inputPresentationPrefix("{\"nested\":{\"inputPresentation\":[]}}"))
        assertFailsWith<AiFailure> { inputPresentationPrefix("{\"inputPresentation\":[]") }
        assertFailsWith<AiFailure> { inputPresentationPrefix("{\"inputPresentation\":[{\"kind\":\"NPC\",\"text\":\"Привет\"}]") }
    }

    @Test fun singleProviderStreamPublishesSpeechBeforeRestOfResponseArrives() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val heard = CompletableDeferred<List<InputUtterance>>()
        val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, "Ты видел курьера?"))
        var calls = 0
        fun chunk(text: String? = null, finish: String? = null): String = "data: " + buildJsonObject {
            put("id", "stream-test"); put("object", "chat.completion.chunk"); put("created", 1); put("model", "gpt-6-luna")
            putJsonArray("choices") { addJsonObject {
                put("index", 0); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
                putJsonObject("delta") { if (text != null) put("content", text) }
            } }
        } + "\n\n"
        val provider = LocalTextProvider.start { call ->
            calls++
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            call.respondTextWriter(ContentType.Text.EventStream) {
                write(chunk("{\"action\":null,\"inputPresentation\":" + Json.encodeToString(parts)))
                flush()
                release.await()
                write(chunk(",\"narration\":\"Он во дворе.\",\"plan\":null,\"nextPlayerId\":\"player-1\",\"speakerNpcId\":null}"))
                write(chunk(finish = "stop"))
                write("data: [DONE]\n\n")
            }
        }
        val gm = ClaudeGameMaster(ClaudeSettings("test-key", "https://ai.starimg.ru", "gpt-6-luna"),
            baseClient = provider.client)
        try {
            val state = AdventureEngine().newGame()
            val participation = ParticipationMemory().also { it.reset(state) }.snapshot(state, state.players.map { it.id }.toSet())
            val result = async { gm.respond(state, "Ты видел курьера?", participation) { heard.complete(it) } }
            assertEquals(parts, withTimeout(3000) { heard.await() })
            assertFalse(result.isCompleted, "Speech must start before the model completes its response")
            release.complete(Unit)
            assertEquals(parts, withTimeout(3000) { result.await() }.inputPresentation)
            assertEquals(1, calls)
        } finally { release.complete(Unit); gm.close(); provider.close() }
    }
}
