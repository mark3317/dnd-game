package ru.markn.dnd.server

import kotlinx.serialization.json.*
import kotlin.test.*
import ru.markn.dnd.adventure.*

class SceneDecisionSchemaTest {
    @Test fun modelCanChooseEncounterButCannotClaimAnyCombatOutcome() {
        val scenario = ArchiveDemo.definition
        val encounter = requireNotNull(scenario.encounter)
        val schema = sceneDecisionSchema(scenario)
        val plan = schema["properties"]!!.jsonObject["plan"]!!.jsonObject["anyOf"]!!.jsonArray.first().jsonObject
        val effects = plan["properties"]!!.jsonObject["onSuccess"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray
        val facts = effects.single {
            it.jsonObject["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content == "fact"
        }.jsonObject["properties"]!!.jsonObject["fact"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(encounter.variants.isNotEmpty(), "Exercise alternative encounter outcomes")
        assertTrue(encounter.triggerFact in facts)
        encounter.variants.forEach { assertTrue(it.triggerFact in facts) }
        assertEquals(scenario.facts.keys - encounter.serverOwnedFacts, facts)
        encounter.serverOwnedFacts.forEach { assertFalse(it in facts, "Model must not set $it") }
    }
}
