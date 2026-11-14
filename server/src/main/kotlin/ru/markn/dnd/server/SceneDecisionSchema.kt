package ru.markn.dnd.server

import kotlinx.serialization.json.*
import ru.markn.dnd.adventure.*

/** The provider may omit unused narration for mechanics; effects remain subject to engine validation. */
internal fun sceneDecisionSchema(scenario: ScenarioDefinition = Lighthouse.definition): JsonObject {
    fun type(name: String) = buildJsonObject { put("type", name) }
    fun choice(values: List<String>) = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    fun obj(vararg fields: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
        put("properties", JsonObject(fields.toMap()))
    }
    fun nullable(value: JsonObject) = buildJsonObject { put("anyOf", JsonArray(listOf(value, type("null")))) }
    fun array(value: JsonObject) = buildJsonObject { put("type", "array"); put("items", value) }
    val item = choice(scenario.items.keys.toList())
    val effects = buildJsonObject {
        put("anyOf", JsonArray(listOf(
            obj("type" to choice(listOf("move")), "location" to choice(scenario.sites.keys.toList())),
            obj("type" to choice(listOf("transfer")), "item" to item, "targetPlayerId" to type("string")),
            obj("type" to choice(listOf("fact")), "fact" to choice(scenario.facts.keys.toList())),
            obj("type" to choice(listOf("damage")), "amount" to type("integer")),
        )))
    }
    val plan = obj(
        "description" to type("string"),
        "check" to nullable(obj("ability" to choice(Ability.entries.map { it.name }), "difficulty" to type("integer"))),
        "usingItem" to nullable(item), "onSuccess" to array(effects), "onFailure" to array(effects),
    )
    return obj("action" to nullable(choice(scenario.actions.map { it.id })),
        "narration" to nullable(type("string")), "plan" to nullable(plan), "nextPlayerId" to type("string"))
}
