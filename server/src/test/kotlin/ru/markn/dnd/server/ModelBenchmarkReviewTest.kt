package ru.markn.dnd.server

import java.nio.file.*
import kotlinx.serialization.json.*
import kotlin.test.*
import ru.markn.dnd.adventure.*

/** Offline replay only: model errors are recorded, never patched into valid decisions. */
class ModelBenchmarkReviewTest {
    @Test fun npcDialogueMayUseEitherTalkOrSocialWithoutEffects() {
        val case = demoAiCases().single { it.id == "GM02" }
        for (action in listOf(null, "TALK")) {
            val reply = GameMasterDecision(action, "Спасите бумаги в подвале.", nextPlayerId = case.state.activePlayer.id, speakerNpcId = "ARCHIVIST")
            assertTrue(assessDemoDecision(case, reply).classificationMatches)
            assertFalse(assessDemoDecision(case, reply.copy(speakerNpcId = null)).classificationMatches)
        }
    }
    @Test fun reviewCapturedRepliesAgainstActualEngine() {
        val source = System.getenv("DND_MODEL_REVIEW") ?: return
        val case = demoAiCases().single { it.id == "GM02" }
        val rows = Files.readAllLines(Path.of(source)).map { Json.parseToJsonElement(it).jsonObject }
        val results = rows.filter { "output" in it }.map { row -> buildJsonObject {
            put("model", row.getValue("model")); put("round", row.getValue("round"))
            try {
                val reply = ClaudeGameMaster.decodeDecision(row.getValue("output").jsonPrimitive.content, case.state.scenario)
                val assessment = assessDemoDecision(case, reply)
                put("contract_valid", true)
                put("classification_matches", assessment.classificationMatches)
                put("engine_accepted", assessment.engineAccepted)
                put("invariants_kept", assessment.invariantsKept)
                put("presentation_valid", reply.inputPresentation != null)
                put("npc_correct", reply.speakerNpcId == "ARCHIVIST")
                put("keeps_focus", reply.nextPlayerId == case.state.activePlayer.id)
                put("technical_prose", Regex("d20|модификатор|сложность|бонус|нейросет|сервер|нажмите|проверок", RegexOption.IGNORE_CASE).containsMatchIn(reply.narration))
                put("narration", reply.narration)
            } catch (failure: Exception) {
                put("contract_valid", false)
                put("reason", (failure as? AiFailure)?.reason ?: "decode_failed")
            }
        } }
        Files.writeString(Path.of(source + ".review.json"), JsonArray(results).toString())
        assertEquals(rows.count { "output" in it }, results.size)
    }
}
