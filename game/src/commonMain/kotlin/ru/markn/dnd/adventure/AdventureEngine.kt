package ru.markn.dnd.adventure

import kotlin.random.Random
import ru.markn.dnd.adventure.diagnostics.GameLogEvent
import ru.markn.dnd.adventure.diagnostics.GameLogger

fun interface DiceRoller { fun roll(sides: Int): Int }

/** Executes validated plans for any registered scenario; the server owns authorization and dice. */
class AdventureEngine(
    private val dice: DiceRoller = DiceRoller { sides -> Random.nextInt(1, sides + 1) },
    private val logger: GameLogger = GameLogger.None,
) {
    fun newGame(sessionId: Long = 1, players: List<Player>? = null, scenarioId: String = Lighthouse.ID): AdventureState {
        val scenario = requireNotNull(Scenarios.find(scenarioId)) { "Unknown scenario" }
        val party = (players ?: demoPlayers(scenarioId)).toList()
        require(party.all { it.location in scenario.sites && it.hero.inventory.all { item -> item in scenario.items } })
        return AdventureState(sessionId, party, TurnOrder(party.map { it.id }), scenario.checkBudget,
            listOf(JournalEntry(1, EntryKind.NARRATOR, scenario.introduction)), scenarioId = scenarioId).also { state ->
            log("session_started", state, details = mapOf("players" to party.size.toString(),
                "queue" to state.turnOrder.playerIds.joinToString(","), "scenario" to scenarioId))
        }
    }

    fun submit(state: AdventureState, playerId: String, turnId: TurnId, input: String,
               decision: GameMasterDecision? = null): AdventureState {
        val text = input.trim()
        if (state.ending != null) return rejected(state, "submit", playerId, "adventure_finished")
        if (state.pendingCheck != null) return rejected(state, "submit", playerId, "pending_check")
        if (state.activePlayer.id != playerId) return rejected(state, "submit", playerId, "wrong_player")
        if (state.turnId != turnId) return rejected(state, "submit", playerId, "stale_turn",
            mapOf("requested_session" to turnId.sessionId.toString(), "requested_turn" to turnId.number.toString()))
        if (text.isEmpty()) return rejected(state, "submit", playerId, "blank_input")
        if (text.length > MAX_ACTION_LENGTH) return rejected(state, "submit", playerId, "input_too_long")
        if (decision?.plan != null && decision.action != null) return rejected(state, "submit", playerId, "ambiguous_plan")
        val action = if (decision != null) state.scenario.actions.firstOrNull { it.id == decision.action }
            else state.scenario.actions.firstOrNull { it.text.lowercase() == text.lowercase().trimEnd('.', '!', '?') }
        if (decision?.action != null && action == null) return rejected(state, "submit", playerId, "unknown_action")
        val plan = decision?.plan ?: action?.planFor(state)
        val invalid = plan?.let { SceneRules.validate(state, it) }
        if (decision?.plan != null && invalid != null) return rejected(state, "submit", playerId, invalid)
        val actor = state.activePlayer
        var next = state.append(EntryKind.PLAYER, text, actor = actor)
        val available = action == null || action.locations.isEmpty() || state.location in action.locations
        next = when {
            !available || invalid != null -> next.append(EntryKind.NARRATOR,
                "Действие недоступно: проверьте место, снаряжение и выполненные условия. Ход сохранён.", actor = actor)
            plan != null -> {
                if (plan.check != null) {
                    val success = plan.onSuccess.joinToString(" ") { SceneRules.describe(state, it) }
                    val failure = if (plan.onFailure.isEmpty()) "сцена не изменится." else plan.onFailure.joinToString(" ") { SceneRules.describe(state, it) }
                    next.append(EntryKind.NARRATOR, "Предложена проверка: ${plan.description}. При успехе: $success При провале: $failure При любом исходе будет потрачена одна общая проверка.", actor = actor)
                        .copy(pendingCheck = PendingCheck(CheckId(state.sessionId, next.journal.last().id),
                            playerId, turnId, action?.id, plan.check.ability, plan.check.difficulty, plan.description, plan))
                } else next.resolveEffects(plan.onSuccess).advanceTurn()
            }
            action?.narrative != null -> {
                val site = state.scenario.sites.getValue(state.location)
                val narration = decision?.narration ?: when (action.narrative) {
                    NarrativeKind.INSPECTION -> site.inspection
                    NarrativeKind.CONVERSATION -> site.conversation
                }
                next.append(EntryKind.NARRATOR, narration, actor = actor).advanceTurn()
            }
            else -> next.append(EntryKind.NARRATOR, decision?.narration ?:
                "Пробный ведущий понимает действия из подсказок. Выберите подсказку или введите её текст. Ход сохранён.", actor = actor)
        }
        return recordTransition(state, next, "submit", playerId, mapOf("action" to (action?.id ?: if (plan != null) "SCENE_PLAN" else "UNSUPPORTED"),
            "input_length" to text.length.toString(), "outcome" to when {
                plan == null && action == null -> "unsupported"
                next.turnId == state.turnId && next.pendingCheck == null -> "unavailable"
                else -> "accepted"
            }))
    }

    fun roll(state: AdventureState, playerId: String, checkId: CheckId): AdventureState {
        val reason = state.checkRejectionReason(playerId, checkId)
        if (reason != null) return rejected(state, "roll", playerId, reason, mapOf("requested_check" to checkId.actionId.toString()))
        val check = requireNotNull(state.pendingCheck)
        val plan = requireNotNull(check.plan)
        SceneRules.validate(state, plan)?.let { return rejected(state, "roll", playerId, it) }
        val actor = state.activePlayer
        val die = try { dice.roll(20) } catch (failure: Exception) {
            log("dice_failed", state, playerId, mapOf("reason" to "dice_source_exception", "type" to (failure::class.simpleName ?: "Exception")))
            throw failure
        }
        if (die !in 1..20) log("dice_failed", state, playerId, mapOf("reason" to "invalid_result", "die" to die.toString()))
        require(die in 1..20) { "A d20 result must be between 1 and 20" }
        val result = RollResult(check.id, actor.id, check.ability, die, actor.hero.modifier(check.ability), check.difficulty)
        val checks = (state.checksRemaining - 1).coerceAtLeast(0)
        var next = state.copy(pendingCheck = null, checksRemaining = checks)
            .append(EntryKind.CHECK, if (result.succeeded) "Проверка пройдена" else "Проверка не пройдена", result, actor)
            .resolveEffects(if (result.succeeded) plan.onSuccess else plan.onFailure)
        if (next.ending == null && checks == 0) next = next.finish(state.scenario.timeoutEnding)
        if (next.ending == null) next = next.advanceTurn()
        return recordTransition(state, next, "roll", playerId)
    }

    fun cancelCheck(state: AdventureState, playerId: String, checkId: CheckId): AdventureState {
        val reason = state.checkRejectionReason(playerId, checkId)
        if (reason != null) return rejected(state, "cancel_check", playerId, reason)
        val result = state.copy(pendingCheck = null).append(
            EntryKind.NARRATOR, "Попытка отменена до броска. Запас проверок сохранён, ход остаётся у этого игрока.",
            actor = state.activePlayer,
        )
        return recordTransition(state, result, "cancel_check", playerId)
    }

    fun endTurn(state: AdventureState, playerId: String, turnId: TurnId): AdventureState {
        if (state.ending != null) return rejected(state, "end_turn", playerId, "adventure_finished")
        if (state.pendingCheck != null) return rejected(state, "end_turn", playerId, "pending_check")
        if (state.activePlayer.id != playerId) return rejected(state, "end_turn", playerId, "wrong_player")
        if (state.turnId != turnId) return rejected(state, "end_turn", playerId, "stale_turn", mapOf(
            "requested_session" to turnId.sessionId.toString(), "requested_turn" to turnId.number.toString(),
        ))
        val next = state.append(EntryKind.PLAYER, "Передаю ход.", actor = state.activePlayer).advanceTurn()
        return recordTransition(state, next, "end_turn", playerId)
    }

    private fun AdventureState.isCurrentTurn(playerId: String, commandTurn: TurnId): Boolean =
        activePlayer.id == playerId && turnId == commandTurn

    private fun AdventureState.checkRejectionReason(playerId: String, checkId: CheckId): String? {
        if (ending != null) return "adventure_finished"
        val check = pendingCheck ?: return "no_pending_check"
        if (check.id != checkId) return "stale_check"
        if (check.playerId != playerId) return "wrong_check_owner"
        if (!isCurrentTurn(playerId, check.turnId)) return "stale_turn"
        return null
    }

    private fun rejected(
        state: AdventureState, command: String, playerId: String, reason: String,
        details: Map<String, String> = emptyMap(),
    ): AdventureState {
        log("command_rejected", state, playerId, mapOf("command" to command, "reason" to reason) + details)
        return state
    }

    private fun recordTransition(
        before: AdventureState, after: AdventureState, command: String, playerId: String,
        details: Map<String, String> = emptyMap(),
    ): AdventureState {
        log("command_completed", after, playerId, mapOf(
            "command" to command,
            "previous_turn" to before.turnOrder.turnNumber.toString(),
            "checks_remaining" to after.checksRemaining.toString(),
        ) + details)
        val check = after.pendingCheck
        if (check != null && check.id != before.pendingCheck?.id) {
            log("check_requested", after, check.playerId, mapOf(
                "check_id" to check.id.actionId.toString(),
                "action" to (check.action ?: "SCENE_PLAN"),
                "ability" to check.ability.name,
                "difficulty" to check.difficulty.toString(),
            ))
        }
        if (command == "roll") {
            val result = after.journal.lastOrNull { it.roll != null }?.roll
            if (result != null) {
                log("check_resolved", after, result.playerId, mapOf(
                    "check_id" to result.checkId.actionId.toString(),
                    "check_turn" to before.turnOrder.turnNumber.toString(),
                    "ability" to result.ability.name,
                    "die" to result.die.toString(),
                    "modifier" to result.modifier.toString(),
                    "total" to result.total.toString(),
                    "difficulty" to result.difficulty.toString(),
                    "succeeded" to result.succeeded.toString(),
                ))
            }
        }
        after.players.forEach { player ->
            val previous = before.players.firstOrNull { it.id == player.id } ?: return@forEach
            if (player.hero.health != previous.hero.health || player.location != previous.location) {
                log("hero_changed", after, player.id, mapOf(
                    "health_before" to previous.hero.health.toString(),
                    "health_after" to player.hero.health.toString(),
                    "location_before" to previous.location,
                    "location_after" to player.location,
                ))
            }
        }
        if (before.scene != after.scene) log("scene_changed", after, playerId, mapOf(
            "facts" to after.scene.facts.sorted().joinToString(","),
        ))
        if (before.players.map { it.hero.inventory } != after.players.map { it.hero.inventory }) {
            log("inventory_changed", after, playerId)
        }
        if (after.turnId != before.turnId) {
            log("turn_changed", after, playerId, mapOf(
                "previous_player" to before.activePlayer.id,
                "next_player" to after.activePlayer.id,
                "previous_turn" to before.turnOrder.turnNumber.toString(),
                "previous_round" to before.turnOrder.roundNumber.toString(),
            ))
        }
        if (after.ending != null && before.ending == null) {
            log("adventure_finished", after, playerId, mapOf("ending" to after.ending))
        }
        return after
    }

    private fun log(
        name: String, state: AdventureState, playerId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        try {
            logger.log(GameLogEvent(name, mapOf(
                "session" to state.sessionId.toString(),
                "turn" to state.turnOrder.turnNumber.toString(),
                "round" to state.turnOrder.roundNumber.toString(),
                "active_player" to state.activePlayer.id,
            ) + (playerId?.let { mapOf("player" to it) } ?: emptyMap()) + details))
        } catch (_: Exception) {
            // Logging is best-effort and must not prevent a valid game transition.
        }
    }

    private fun AdventureState.resolveEffects(effects: List<SceneEffect>): AdventureState {
        var next = this
        if (effects.isEmpty()) next = next.append(EntryKind.NARRATOR, "Попытка не изменила сцену. Время на проверку потрачено.", actor = activePlayer)
        for (effect in effects) {
            val description = SceneRules.describe(next, effect)
            next = SceneRules.apply(next, effect).append(EntryKind.NARRATOR, description, actor = activePlayer)
        }
        val ending = next.scene.facts.firstNotNullOfOrNull { next.scenario.facts[it]?.ending }
        return if (ending != null) next.finish(ending) else next
    }

    private fun AdventureState.advanceTurn(): AdventureState {
        val next = copy(turnOrder = turnOrder.next())
        return next.append(
            EntryKind.TURN,
            "Ход: ${next.activePlayer.name} · ${next.activePlayer.hero.name}. Круг ${next.turnOrder.roundNumber}.",
            actor = next.activePlayer,
        )
    }

    private fun AdventureState.finish(result: String): AdventureState =
        copy(ending = result, pendingCheck = null).append(EntryKind.NARRATOR, scenario.endings.getValue(result).description)

    private fun AdventureState.append(
        kind: EntryKind, text: String, roll: RollResult? = null, actor: Player? = null,
    ): AdventureState = copy(
        journal = journal + JournalEntry(
            (journal.lastOrNull()?.id ?: 0) + 1, kind, text, roll, actor?.id, actor?.hero?.name,
        ),
    )

    companion object {
        const val MAX_ACTION_LENGTH = 500

        fun demoPlayers(scenarioId: String = Lighthouse.ID): List<Player> {
            val scenario = requireNotNull(Scenarios.find(scenarioId))
            val rin = Hero(
                "Рин", "Следопыт", 12, 12,
                mapOf(
                    Ability.STRENGTH to 1, Ability.DEXTERITY to 3, Ability.CONSTITUTION to 2,
                    Ability.INTELLIGENCE to 1, Ability.WISDOM to 2, Ability.CHARISMA to 0,
                ),
                scenario.startingItems,
            )
            val mira = rin.copy(
                name = "Мира", archetype = "Учёная", health = 10, maxHealth = 10,
                modifiers = mapOf(
                    Ability.STRENGTH to 0, Ability.DEXTERITY to 1, Ability.CONSTITUTION to 1,
                    Ability.INTELLIGENCE to 3, Ability.WISDOM to 2, Ability.CHARISMA to 1,
                ),
            )
            val bran = rin.copy(
                name = "Бран", archetype = "Защитник", health = 16, maxHealth = 16,
                modifiers = mapOf(
                    Ability.STRENGTH to 3, Ability.DEXTERITY to 0, Ability.CONSTITUTION to 3,
                    Ability.INTELLIGENCE to 0, Ability.WISDOM to 1, Ability.CHARISMA to 1,
                ),
            )
            return listOf(Player("player-1", "Игрок 1", rin, scenario.startLocation),
                Player("player-2", "Игрок 2", mira, scenario.startLocation), Player("player-3", "Игрок 3", bran, scenario.startLocation))
        }
    }
}
