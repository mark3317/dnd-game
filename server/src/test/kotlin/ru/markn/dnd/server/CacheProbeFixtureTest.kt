package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.test.*
import ru.markn.dnd.adventure.diagnostics.GameLogger

class CacheProbeFixtureTest {
    @Test fun exportRealRequestOnlyWhenExplicitlyRequested() = runBlocking {
        val target = System.getenv("DND_CACHE_PROBE_FIXTURE") ?: return@runBlocking
        val case = demoAiCases().single { it.id == "GM02" }
        val provider = HttpClient(MockEngine { request ->
            val body = (request.body as TextContent).text
            val parsed = Json.parseToJsonElement(body).jsonObject
            assertEquals("gpt-6-luna", parsed["model"]!!.jsonPrimitive.content)
            Files.writeString(Path.of(target), body, StandardOpenOption.CREATE_NEW)
            respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val gm = ClaudeGameMaster(ClaudeSettings("test-key", "https://ai.starimg.ru", "gpt-6-luna"), GameLogger.None, provider)
        try { assertFails { gm.respond(case.state,case.input,case.participation) } } finally { gm.close() }
    }
}
