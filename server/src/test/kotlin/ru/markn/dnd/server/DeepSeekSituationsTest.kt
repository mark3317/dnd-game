package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.GameLogger

class DeepSeekSituationsTest {
    @Test fun exportFixturesOffline() = runBlocking {
        val target = System.getenv("DND_DEEPSEEK_FIXTURES") ?: return@runBlocking
        val ids = listOf("GM02", "GM04", "GM08", "GM10", "GM11", "GM13", "GM14", "GM18", "GM28")
        val rows = mutableListOf<JsonElement>()
        for (id in ids) {
            val case = demoAiCases().single { it.id == id }
            val provider = HttpClient(MockEngine { request ->
                rows += buildJsonObject {
                    put("case", id); put("input", case.input); put("rubric", case.rubric)
                    put("outcome", case.outcome != null)
                    put("body", Json.parseToJsonElement((request.body as TextContent).text))
                }
                respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType,"application/json"))
            })
            val gm = ClaudeGameMaster(ClaudeSettings("test-key", "https://ai.starimg.ru", "gpt-6-luna"), GameLogger.None, provider)
            try { assertFails {
                if (case.outcome != null) gm.narrate(case.outcome) else gm.respond(case.state,case.input,case.participation)
            } } finally { gm.close() }
        }
        assertEquals(9, rows.size)
        Files.writeString(Path.of(target), JsonArray(rows).toString(), StandardOpenOption.CREATE_NEW)
    }
    @Test fun reviewResponsesOffline() {
        val source = System.getenv("DND_DEEPSEEK_REVIEW") ?: return
        val corpus = demoAiCases()
        val rows = Files.readAllLines(Path.of(source)).map { Json.parseToJsonElement(it).jsonObject }
        val result = rows.filter { "output" in it }.map { row -> buildJsonObject {
            val case = corpus.single { it.id == row.getValue("case").jsonPrimitive.content }
            put("attempt", row.getValue("attempt")); put("case", case.id)
            try {
                val text = row.getValue("output").jsonPrimitive.content
                if (case.outcome != null) {
                    put("narration", ClaudeGameMaster.decodeOutcome(text))
                    put("outcome_requires_semantic_review", true)
                } else {
                    val reply = ClaudeGameMaster.decodeDecision(text, case.state.scenario)
                    val assessment = assessDemoDecision(case, reply)
                    put("classification", assessment.classification)
                    put("classification_matches", assessment.classificationMatches)
                    put("engine_accepted", assessment.engineAccepted)
                    put("invariants_kept", assessment.invariantsKept)
                    assessment.planIssue?.let { put("plan_issue", it) }
                    put("presentation_valid", reply.inputPresentation != null)
                    put("decision", Json.encodeToJsonElement(reply))
                    val after = AdventureEngine().submit(case.state, case.state.activePlayer.id, case.state.turnId, case.input, reply)
                    put("state_changed", after != case.state)
                    put("pending_check", after.pendingCheck?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
                }
                put("contract_valid", true)
            } catch (e: Exception) {
                put("contract_valid", false); put("reason", (e as? AiFailure)?.reason ?: "decode_failed")
            }
        } }
        Files.writeString(Path.of(source + ".review.json"), JsonArray(result).toString())
    }
}
