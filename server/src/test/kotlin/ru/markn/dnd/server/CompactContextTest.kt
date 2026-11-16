package ru.markn.dnd.server

import kotlinx.serialization.json.*
import kotlin.test.*
import ru.markn.dnd.adventure.*

class CompactContextTest {
    @Test fun compactContextPreservesRulesFactsAndRecentMemoryForEveryFixture() {
        for (case in demoAiCases()) {
            val full = Json.parseToJsonElement(ClaudeGameMaster.context(case.state, case.input, case.participation)).jsonObject
            val compact = Json.parseToJsonElement(ClaudeGameMaster.context(case.state, case.input, case.participation, true)).jsonObject
            assertTrue(compact.toString().length < full.toString().length, case.id)
            for (key in listOf("current_facts", "participation", "player_intent", "active_player_id"))
                assertEquals(full[key], compact[key], "${case.id}: $key")
            val scenario = Json.decodeFromJsonElement<ScenarioDefinition>(compact.getValue("scenario"))
            assertEquals(case.state.scenario.facts, scenario.facts)
            assertEquals(case.state.scenario.sites, scenario.sites)
            assertEquals(case.state.scenario.routes, scenario.routes)
            assertEquals(case.state.scenario.encounter, scenario.encounter)
            assertEquals(case.state.scenario.actions.filter { it.locations.isEmpty() || case.state.activePlayer.location in it.locations }, scenario.actions)
            val restored = Json.decodeFromJsonElement<AdventureState>(JsonObject(compact.getValue("state").jsonObject + ("sessionId" to JsonPrimitive(case.state.sessionId))))
            assertEquals(case.state.players, restored.players)
            assertEquals(case.state.scene, restored.scene)
            assertEquals(case.state.pendingCheck, restored.pendingCheck)
            assertEquals(case.state.combat, restored.combat)
            assertEquals(case.state.checksRemaining, restored.checksRemaining)
            assertEquals(Json.decodeFromJsonElement<AdventureState>(full.getValue("state")).journal, restored.journal)
        }
    }
}
