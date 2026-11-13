package ru.markn.dnd.adventure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SceneState(val facts: Set<String> = emptySet())

@Serializable
data class ProposedCheck(val ability: Ability, val difficulty: Int)

/** Both branches are frozen before the server rolls any dice. IDs belong to a scenario. */
@Serializable
data class SceneActionPlan(
    val description: String,
    val check: ProposedCheck?,
    val usingItem: String?,
    val onSuccess: List<SceneEffect>,
    val onFailure: List<SceneEffect>,
)

@Serializable
sealed interface SceneEffect {
    @Serializable @SerialName("move")
    data class Move(val location: String) : SceneEffect
    @Serializable @SerialName("transfer")
    data class Transfer(val item: String, val targetPlayerId: String) : SceneEffect
    @Serializable @SerialName("fact")
    data class Fact(val fact: String) : SceneEffect
    @Serializable @SerialName("damage")
    data class Damage(val amount: Int) : SceneEffect
}

/** Generic bounds and ownership; scenario restrictions are declarative data. */
object SceneRules {
    private fun requirements(state: AdventureState, rule: Requirements, plan: SceneActionPlan): String? {
        if (rule.location != null && state.location != rule.location) return "wrong_location"
        if (!state.scene.facts.containsAll(rule.facts)) return "missing_facts"
        if (rule.usingItem != null && (plan.usingItem != rule.usingItem || rule.usingItem !in state.activePlayer.hero.inventory)) return "missing_item"
        val minimum = rule.difficulty(state)
        if (minimum != null && (plan.check == null || plan.check.difficulty < minimum)) return "check_required"
        return null
    }

    fun minimumDifficulty(state: AdventureState, effects: List<SceneEffect>): Int? = effects.mapNotNull { effect ->
        when (effect) {
            is SceneEffect.Move -> state.scenario.routes.firstOrNull { it.from == state.location && it.to == effect.location }?.requirements?.difficulty(state)
            is SceneEffect.Fact -> state.scenario.facts[effect.fact]?.requirements?.difficulty(state)
            else -> null
        }
    }.maxOrNull()

    fun validate(state: AdventureState, plan: SceneActionPlan): String? {
        if (plan.description.isBlank() || plan.description.length > 500) return "invalid_description"
        if (plan.check != null && plan.check.difficulty !in 8..18) return "invalid_difficulty"
        if (plan.onSuccess.size !in 1..4 || plan.onFailure.size > 2) return "invalid_effect_count"
        if (plan.check == null && plan.onFailure.isNotEmpty()) return "unchecked_failure"
        if (plan.usingItem != null && (plan.usingItem !in state.scenario.items || plan.usingItem !in state.activePlayer.hero.inventory)) return "missing_item"
        if (plan.onSuccess.any { it is SceneEffect.Damage } || plan.onFailure.any { it !is SceneEffect.Damage }) return "invalid_branch"
        val damage = plan.onFailure.filterIsInstance<SceneEffect.Damage>()
        if (damage.sumOf { it.amount.toLong() } !in 0L..3L || damage.any { it.amount !in 1..3 }) return "invalid_damage"
        if (plan.onSuccess.filterIsInstance<SceneEffect.Fact>().count { state.scenario.facts[it.fact]?.ending != null } > 1) return "multiple_objectives"
        for (effects in listOf(plan.onSuccess, plan.onFailure)) {
            var simulated = state
            for (effect in effects) {
                when (effect) {
                    is SceneEffect.Move -> {
                        val route = state.scenario.routes.firstOrNull { it.from == simulated.location && it.to == effect.location }
                            ?: return "unreachable_location"
                        val rule = route.requirements
                        requirements(simulated, rule.copy(easierWhen = null, easierDifficulty = null,
                            minimumDifficulty = rule.difficulty(state)), plan)?.let { return it }
                    }
                    is SceneEffect.Transfer -> {
                        if (effect.item !in state.scenario.items || effect.item !in simulated.activePlayer.hero.inventory) return "missing_item"
                        val target = simulated.players.firstOrNull { it.id == effect.targetPlayerId } ?: return "unknown_target"
                        if (target.id == simulated.activePlayer.id || target.location != simulated.location) return "target_unreachable"
                    }
                    is SceneEffect.Fact -> {
                        val fact = state.scenario.facts[effect.fact] ?: return "unknown_fact"
                        if (effect.fact in simulated.scene.facts) return "fact_already_set"
                        // Bonuses must predate this action, rather than discount their own acquisition.
                        val rule = fact.requirements
                        requirements(simulated, rule.copy(easierWhen = null, easierDifficulty = null,
                            minimumDifficulty = rule.difficulty(state)), plan)?.let { return it }
                    }
                    is SceneEffect.Damage -> Unit
                }
                simulated = apply(simulated, effect)
            }
        }
        return null
    }

    internal fun apply(state: AdventureState, effect: SceneEffect): AdventureState {
        val actor = state.activePlayer
        fun update(player: Player) = state.copy(players = state.players.map { if (it.id == player.id) player else it })
        fun withoutItem(item: String) = update(actor.copy(hero = actor.hero.copy(
            inventory = actor.hero.inventory.toMutableList().also { it.remove(item) })))
        return when (effect) {
            is SceneEffect.Move -> update(actor.copy(location = effect.location))
            is SceneEffect.Damage -> update(actor.copy(hero = actor.hero.copy(health = (actor.hero.health - effect.amount).coerceAtLeast(0))))
            is SceneEffect.Transfer -> state.copy(players = state.players.map { player ->
                when (player.id) {
                    actor.id -> player.copy(hero = player.hero.copy(inventory = player.hero.inventory.toMutableList().also { it.remove(effect.item) }))
                    effect.targetPlayerId -> player.copy(hero = player.hero.copy(inventory = player.hero.inventory + effect.item))
                    else -> player
                }
            })
            is SceneEffect.Fact -> {
                val fact = state.scenario.facts.getValue(effect.fact)
                val next = if (fact.consumeItem) withoutItem(requireNotNull(fact.requirements.usingItem)) else state
                next.copy(scene = next.scene.copy(facts = next.scene.facts + effect.fact))
            }
        }
    }

    fun describe(state: AdventureState, effect: SceneEffect): String = when (effect) {
        is SceneEffect.Move -> "Место героя: ${state.scenario.sites.getValue(effect.location).title}."
        is SceneEffect.Transfer -> "${state.scenario.items.getValue(effect.item)}: передано герою ${state.players.first { it.id == effect.targetPlayerId }.hero.name}."
        is SceneEffect.Damage -> "Герой получает урон: ${effect.amount}."
        is SceneEffect.Fact -> state.scenario.facts.getValue(effect.fact).description
    }
}
