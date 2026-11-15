package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

@Serializable
data class Site(val title: String, val inspection: String, val conversation: String, val npcId: String? = null,
    val npcName: String? = null, val conversationOverrides: Map<String, String> = emptyMap())

/** Scenario policy is data. The engine has no branches on scenario, location, item or fact IDs. */
@Serializable
data class Requirements(
    val location: String? = null,
    val usingItem: String? = null,
    val facts: Set<String> = emptySet(),
    val minimumDifficulty: Int? = null,
    val easierWhen: String? = null,
    val easierDifficulty: Int? = null,
    val checkDomains: Set<CheckDomain> = emptySet(),
    val forbiddenFacts: Set<String> = emptySet(),
    val allowedAbilities: Set<Ability> = emptySet(),
    val minimumFailureDamage: Int = 0,
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
    val alternativeRequirements: List<Requirements> = emptyList(),
) {
    val allRequirements: List<Requirements> get() = listOf(requirements) + alternativeRequirements
}

@Serializable
data class Route(val from: String, val to: String, val requirements: Requirements = Requirements())

@Serializable
data class Outcome(val title: String, val description: String, val success: Boolean = true)

@Serializable
data class EncounterVariantDefinition(
    val id: String,
    val triggerFact: String,
    val title: String,
    val introduction: String,
    val escortObjective: ru.markn.dnd.combat.EscortObjectiveDefinition,
    val victoryFact: String,
    val victoryEnding: String,
    val expirationFact: String,
)

@Serializable
data class EncounterDefinition(val triggerFact: String, val victoryFact: String, val defeatEnding: String,
    val location: String, val title: String, val introduction: String, val battleLocation: String = location,
    val variants: List<EncounterVariantDefinition> = emptyList(),
) {
    val serverOwnedFacts: Set<String> get() = setOf(victoryFact) + variants.flatMap { listOf(it.victoryFact, it.expirationFact) }
}

@Serializable
enum class NarrativeKind { INSPECTION, CONVERSATION }

@Serializable
data class ScenarioAction(
    val id: String,
    val text: String,
    val locations: Set<String> = emptySet(),
    val narrative: NarrativeKind? = null,
    val plan: SceneActionPlan? = null,
    val contactsNpc: Boolean = false,
    val detail: String? = null,
) {
    fun planFor(state: AdventureState): SceneActionPlan? = plan?.let { original ->
        original.copy(check = original.check?.copy(difficulty = SceneRules.minimumDifficulty(state, original.onSuccess, original)
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
    val encounter: EncounterDefinition? = null,
) {
    init {
        require(id.isNotBlank() && checkBudget > 0 && startLocation in sites && timeoutEnding in endings)
        require(!endings.getValue(timeoutEnding).success)
        require(startingItems.all { it in items })
        require(actions.map { it.id }.distinct().size == actions.size)
        fun valid(r: Requirements) = (r.location == null || r.location in sites) &&
            (r.usingItem == null || r.usingItem in items) && r.facts.all { it in facts } &&
            r.forbiddenFacts.all { it in facts } && r.facts.intersect(r.forbiddenFacts).isEmpty() &&
            (r.minimumDifficulty == null || r.minimumDifficulty in 8..18) &&
            (r.allowedAbilities.isEmpty() || r.minimumDifficulty != null) &&
            r.minimumFailureDamage in 0..3 && (r.minimumFailureDamage == 0 || r.minimumDifficulty != null) &&
            (r.easierWhen == null || r.easierWhen in facts) &&
            (r.easierDifficulty == null || (r.easierWhen != null && r.easierDifficulty in 8..18))
        require(facts.values.all { fact -> fact.allRequirements.all(::valid) &&
            (!fact.consumeItem || (fact.requirements.usingItem != null &&
                fact.alternativeRequirements.all { it.usingItem == fact.requirements.usingItem })) &&
            (fact.ending == null || (fact.ending in endings && fact.allRequirements.all { it.minimumDifficulty != null })) })
        require(routes.all { it.from in sites && it.to in sites && it.from != it.to && valid(it.requirements) })
        require(routes.map { it.from to it.to }.distinct().size == routes.size)
        require(actions.all { action -> action.locations.all { it in sites } && (action.narrative != null) != (action.plan != null) })
        require(actions.all { it.detail == null || (it.detail.isNotBlank() && it.detail.length <= 1_000) })
        require(sites.values.all { site -> site.conversationOverrides.all { (fact, text) -> fact in facts && text.isNotBlank() } })
        require(encounter == null || (encounter.triggerFact in facts && encounter.victoryFact in facts &&
            encounter.defeatEnding in endings && encounter.location in sites && encounter.battleLocation in sites))
        encounter?.let { encounter ->
            require(encounter.variants.map { it.id }.distinct().size == encounter.variants.size)
            require(encounter.variants.map { it.triggerFact }.distinct().size == encounter.variants.size)
            require(encounter.variants.all { it.id.isNotBlank() && it.triggerFact in facts && it.triggerFact != encounter.triggerFact &&
                it.victoryFact in facts && it.expirationFact in facts && it.victoryEnding in endings })
            require(encounter.serverOwnedFacts.none { it == encounter.triggerFact || encounter.variants.any { variant -> variant.triggerFact == it } })
        }
    }
}
