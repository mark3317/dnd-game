package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

@Serializable
data class Site(val title: String, val inspection: String, val conversation: String)

/** Scenario policy is data. The engine has no branches on scenario, location, item or fact IDs. */
@Serializable
data class Requirements(
    val location: String? = null,
    val usingItem: String? = null,
    val facts: Set<String> = emptySet(),
    val minimumDifficulty: Int? = null,
    val easierWhen: String? = null,
    val easierDifficulty: Int? = null,
) {
    fun difficulty(state: AdventureState): Int? =
        if (easierWhen != null && easierWhen in state.scene.facts) easierDifficulty ?: minimumDifficulty else minimumDifficulty
}

@Serializable
data class FactDefinition(
    val description: String,
    val requirements: Requirements = Requirements(),
    val consumeItem: Boolean = false,
    val ending: String? = null,
)

@Serializable
data class Route(val from: String, val to: String, val requirements: Requirements = Requirements())

@Serializable
data class Outcome(val title: String, val description: String, val success: Boolean = true)

@Serializable
enum class NarrativeKind { INSPECTION, CONVERSATION }

@Serializable
data class ScenarioAction(
    val id: String,
    val text: String,
    val locations: Set<String> = emptySet(),
    val narrative: NarrativeKind? = null,
    val plan: SceneActionPlan? = null,
) {
    fun planFor(state: AdventureState): SceneActionPlan? = plan?.let { original ->
        original.copy(check = original.check?.copy(difficulty = SceneRules.minimumDifficulty(state, original.onSuccess)
            ?: original.check.difficulty))
    }
}

@Serializable
data class ScenarioDefinition(
    val id: String,
    val title: String,
    val goal: String,
    val introduction: String,
    val atmosphere: String,
    val pressure: String,
    val checkBudget: Int,
    val startLocation: String,
    val startingItems: List<String>,
    val sites: Map<String, Site>,
    val items: Map<String, String>,
    val facts: Map<String, FactDefinition>,
    val routes: List<Route>,
    val endings: Map<String, Outcome>,
    val timeoutEnding: String,
    val actions: List<ScenarioAction>,
) {
    init {
        require(id.isNotBlank() && checkBudget > 0 && startLocation in sites && timeoutEnding in endings)
        require(!endings.getValue(timeoutEnding).success)
        require(startingItems.all { it in items })
        require(actions.map { it.id }.distinct().size == actions.size)
        fun valid(r: Requirements) = (r.location == null || r.location in sites) &&
            (r.usingItem == null || r.usingItem in items) && r.facts.all { it in facts } &&
            (r.minimumDifficulty == null || r.minimumDifficulty in 8..18) &&
            (r.easierWhen == null || r.easierWhen in facts) &&
            (r.easierDifficulty == null || (r.easierWhen != null && r.easierDifficulty in 8..18))
        require(facts.values.all { valid(it.requirements) && (!it.consumeItem || it.requirements.usingItem != null) &&
            (it.ending == null || (it.ending in endings && it.requirements.minimumDifficulty != null)) })
        require(routes.all { it.from in sites && it.to in sites && it.from != it.to && valid(it.requirements) })
        require(routes.map { it.from to it.to }.distinct().size == routes.size)
        require(actions.all { action -> action.locations.all { it in sites } && (action.narrative != null) != (action.plan != null) })
    }
}
