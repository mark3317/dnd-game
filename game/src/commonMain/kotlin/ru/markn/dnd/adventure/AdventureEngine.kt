package ru.markn.dnd.adventure

import kotlin.random.Random
import ru.markn.dnd.combat.*
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
        if (state.combat != null) return rejected(state, "submit", playerId, "combat_active")
        if (state.ending != null) return rejected(state, "submit", playerId, "adventure_finished")
        if (state.pendingCheck != null) return rejected(state, "submit", playerId, "pending_check")
        if (state.activePlayer.id != playerId) return rejected(state, "submit", playerId, "wrong_player")
        if (state.turnId != turnId) return rejected(state, "submit", playerId, "stale_turn",
            mapOf("requested_session" to turnId.sessionId.toString(), "requested_turn" to turnId.number.toString()))
        if (text.isEmpty()) return rejected(state, "submit", playerId, "blank_input")
        if (text.length > MAX_ACTION_LENGTH) return rejected(state, "submit", playerId, "input_too_long")
        if (decision?.plan != null && decision.action != null) return rejected(state, "submit", playerId, "ambiguous_plan")
        if (decision?.nextPlayerId != null && decision.nextPlayerId !in state.turnOrder.playerIds)
            return rejected(state, "submit", playerId, "unknown_next_player")
        if (decision != null && decision.action == null && decision.plan == null &&
            decision.nextPlayerId != null && decision.nextPlayerId != playerId)
            return rejected(state, "submit", playerId, "comment_cannot_pass_turn")
        val action = if (decision != null) state.scenario.actions.firstOrNull { it.id == decision.action }
            else state.scenario.actions.firstOrNull { it.text.lowercase() == text.lowercase().trimEnd('.', '!', '?') }
        if (decision?.action != null && action == null) return rejected(state, "submit", playerId, "unknown_action")
        val plan = decision?.plan ?: action?.planFor(state)
        val site = state.scenario.sites.getValue(state.location)
        if (decision?.speakerNpcId != null && (decision.speakerNpcId != site.npcId || plan != null ||
            action?.narrative == NarrativeKind.INSPECTION)) return rejected(state, "submit", playerId, "invalid_speaker")
        val invalid = plan?.let { SceneRules.validate(state, it) }
        if (decision?.plan != null && invalid != null) return rejected(state, "submit", playerId, invalid)
        val actor = state.activePlayer
        val presentation = decision?.inputPresentation ?: if (decision == null && action != null) listOf(
            InputUtterance(InputUtteranceKind.ACTION, when (action.narrative) {
                NarrativeKind.INSPECTION -> "${actor.hero.name} осматривается."
                NarrativeKind.CONVERSATION -> "${actor.hero.name} обращается к собеседнику."
                else -> "${actor.hero.name} собирается ${action.text.replaceFirstChar { it.lowercase() }}."
            })) else emptyList()
        if ((decision?.inputPresentation != null || presentation.isNotEmpty()) &&
            runCatching { validateInputPresentation(presentation) }.isFailure)
            return rejected(state, "submit", playerId, "invalid_input_presentation")
        var next = state.append(EntryKind.PLAYER, text, actor = actor, speechEligible = false)
        if (presentation.isNotEmpty()) next = next.copy(journal = next.journal + state.inputEntries(presentation))
        val available = action == null || action.locations.isEmpty() || state.location in action.locations
        next = when {
            !available || invalid != null -> next.append(EntryKind.SYSTEM,
                if (invalid == "forbidden_facts" && plan != null) SceneRules.unavailableMessage(state, plan)
                else "Действие недоступно: проверьте место, снаряжение и выполненные условия. Ход сохранён.", actor = actor)
            plan != null -> {
                if (plan.check != null) {
                    val success = plan.onSuccess.joinToString(" ") { SceneRules.describe(state, it) }
                    val failure = if (plan.onFailure.isEmpty()) "сцена не изменится." else plan.onFailure.joinToString(" ") { SceneRules.describe(state, it) }
                    next.append(EntryKind.SYSTEM, "Предложена проверка: ${plan.description}. При успехе: $success При провале: $failure При любом исходе будет потрачена одна общая проверка.", actor = actor)
                        .copy(pendingCheck = PendingCheck(CheckId(state.sessionId, next.journal.last().id),
                            playerId, turnId, action?.id, plan.check.ability, plan.check.difficulty, plan.description, plan,
                            decision?.nextPlayerId))
                } else next.resolveEffects(plan.onSuccess).let { resolved ->
                    if (resolved.ending != null) resolved else resolved.advanceTurn(decision?.nextPlayerId)
                }
            }
            action?.narrative != null -> {
                val narration = decision?.narration ?: when (action.narrative) {
                    NarrativeKind.INSPECTION -> site.inspection
                    NarrativeKind.CONVERSATION -> state.scriptedConversation(site)
                }
                val npc = decision?.speakerNpcId ?: if (action.narrative == NarrativeKind.CONVERSATION) site.npcId else null
                next.append(if (npc == null) EntryKind.NARRATOR else EntryKind.NPC, narration, actor = actor, speakerNpcId = npc)
                    .advanceTurn(decision?.nextPlayerId)
            }
            else -> next.append(if (decision == null) EntryKind.SYSTEM else if (decision.speakerNpcId == null) EntryKind.NARRATOR else EntryKind.NPC,
                decision?.narration ?: "Выберите действие или опишите его ведущему.", actor = actor, speakerNpcId = decision?.speakerNpcId)
        }
        if (next.pendingCheck == null &&
            ((next.turnId != state.turnId && (action?.narrative == NarrativeKind.CONVERSATION || action?.contactsNpc == true)) ||
                decision?.speakerNpcId != null)) next = next.meetNpc(actor)
        return recordTransition(state, next, "submit", playerId, mapOf("action" to (action?.id ?: if (plan != null) "SCENE_PLAN" else "UNSUPPORTED"),
            "input_length" to text.length.toString(), "outcome" to when {
                plan == null && action == null -> if (decision != null) "reply" else "unsupported"
                next.turnId == state.turnId && next.pendingCheck == null -> "unavailable"
                else -> "accepted"
            }))
    }

    fun roll(state: AdventureState, playerId: String, checkId: CheckId, useRacialTalent: Boolean = false): AdventureState {
        val reason = state.checkRejectionReason(playerId, checkId)
        if (reason != null) return rejected(state, "roll", playerId, reason, mapOf("requested_check" to checkId.actionId.toString()))
        val check = requireNotNull(state.pendingCheck)
        val plan = requireNotNull(check.plan)
        SceneRules.validate(state, plan)?.let { return rejected(state, "roll", playerId, it) }
        val actor = state.activePlayer
        val proposed = requireNotNull(plan.check)
        if (useRacialTalent && !CharacterRules.canUseTalent(state, proposed)) return rejected(state, "roll", playerId, "racial_talent_unavailable")
        val die = try { dice.roll(20) } catch (failure: Exception) {
            log("dice_failed", state, playerId, mapOf("reason" to "dice_source_exception", "type" to (failure::class.simpleName ?: "Exception")))
            throw failure
        }
        if (die !in 1..20) log("dice_failed", state, playerId, mapOf("reason" to "invalid_result", "die" to die.toString()))
        require(die in 1..20) { "A d20 result must be between 1 and 20" }
        val bonuses = CharacterRules.bonuses(actor.hero, proposed, useRacialTalent)
        val result = RollResult(check.id, actor.id, check.ability, die, bonuses.total, check.difficulty, bonuses)
        val checks = (state.checksRemaining - 1).coerceAtLeast(0)
        var next = state.copy(pendingCheck = null, checksRemaining = checks,
            players = state.players.map { if (it.id == actor.id && useRacialTalent) it.copy(hero = it.hero.copy(racialTalentUsed = true)) else it })
            .append(EntryKind.CHECK, if (result.succeeded) "Проверка пройдена" else "Проверка не пройдена", result, actor)
            .resolveEffects(if (result.succeeded) plan.onSuccess else plan.onFailure)
        if (CheckDomain.PERSUASION in proposed.domains) next = next.meetNpc(actor)
        if (next.ending == null && checks == 0) next = next.finish(state.scenario.timeoutEnding)
        if (next.ending == null) next = next.advanceTurn(check.nextPlayerId)
        return recordTransition(state, next, "roll", playerId)
    }

    fun cancelCheck(state: AdventureState, playerId: String, checkId: CheckId): AdventureState {
        val reason = state.checkRejectionReason(playerId, checkId)
        if (reason != null) return rejected(state, "cancel_check", playerId, reason)
        val result = state.copy(pendingCheck = null).append(
            EntryKind.SYSTEM, "Попытка отменена до броска. Запас проверок сохранён, ход остаётся у этого игрока.",
            actor = state.activePlayer,
        )
        return recordTransition(state, result, "cancel_check", playerId)
    }

    fun endTurn(state: AdventureState, playerId: String, turnId: TurnId): AdventureState {
        if (state.combat != null) return combatCommand(state, playerId, turnId, null, null)
        if (state.ending != null) return rejected(state, "end_turn", playerId, "adventure_finished")
        if (state.pendingCheck != null) return rejected(state, "end_turn", playerId, "pending_check")
        if (state.activePlayer.id != playerId) return rejected(state, "end_turn", playerId, "wrong_player")
        if (state.turnId != turnId) return rejected(state, "end_turn", playerId, "stale_turn", mapOf(
            "requested_session" to turnId.sessionId.toString(), "requested_turn" to turnId.number.toString(),
        ))
        val next = state.append(EntryKind.PLAYER, "Передаю ход.", actor = state.activePlayer, speechEligible = false)
            .append(EntryKind.NARRATOR, "${state.activePlayer.hero.name} передаёт слово товарищам.", actor = state.activePlayer).advanceTurn()
        return recordTransition(state, next, "end_turn", playerId)
    }

    private fun AdventureState.isCurrentTurn(playerId: String, commandTurn: TurnId): Boolean =
        activePlayer.id == playerId && turnId == commandTurn

    private fun AdventureState.meetNpc(actor: Player): AdventureState {
        val npc = scenario.sites.getValue(actor.location).npcId ?: return this
        return copy(scene = scene.copy(metNpcs = scene.metNpcs + (actor.id to (scene.metNpcs[actor.id].orEmpty() + npc))))
    }

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
        if (effects.isEmpty()) next = next.append(EntryKind.NARRATOR, "${activePlayer.hero.name} пробует, но задуманное пока не удаётся.", actor = activePlayer)
        for (effect in effects) {
            val description = SceneRules.describe(next, effect)
            next = SceneRules.apply(next, effect).append(EntryKind.SYSTEM, description, actor = activePlayer)
            val story = when (effect) {
                is SceneEffect.Fact -> scenario.facts.getValue(effect.fact).description
                is SceneEffect.Move -> "${activePlayer.hero.name} входит в ${scenario.sites.getValue(effect.location).title.lowercase()}."
                is SceneEffect.Transfer -> "${activePlayer.hero.name} передаёт ${scenario.items.getValue(effect.item).lowercase()} товарищу."
                is SceneEffect.Damage -> "${activePlayer.hero.name} отшатывается от боли."
            }
            next = next.append(EntryKind.NARRATOR, story, actor = activePlayer)
        }
        val ending = next.scene.facts.firstNotNullOfOrNull { next.scenario.facts[it]?.ending }
        if (ending != null) return next.finish(ending)
        val encounter = next.scenario.encounter
        val variant = encounter?.variants?.firstOrNull { it.triggerFact in next.scene.facts }
        if (encounter != null && (encounter.triggerFact in next.scene.facts || variant != null) &&
            encounter.victoryFact !in next.scene.facts && next.combat == null) {
            next = next.copy(players = next.players.map { it.copy(location = encounter.battleLocation) })
            next = next.copy(combat = next.createEncounter(dice)).append(EntryKind.NARRATOR, variant?.introduction ?: encounter.introduction)
            next = next.settleCombat()
        }
        return next
    }

    private fun AdventureState.advanceTurn(nextPlayerId: String? = null): AdventureState {
        if (combat != null) return this
        val next = copy(turnOrder = turnOrder.next(nextPlayerId))
        return next.append(
            EntryKind.TURN,
            "Ход: ${next.activePlayer.name} · ${next.activePlayer.hero.name}." +
                if (nextPlayerId == null) " Круг ${next.turnOrder.roundNumber}." else "",
            actor = next.activePlayer,
        )
    }

    private fun AdventureState.finish(result: String): AdventureState =
        copy(ending = result, pendingCheck = null, combat = null).append(EntryKind.NARRATOR, scenario.endings.getValue(result).description)

    /** null action means end turn. Every accepted command advances the turn token, including movement. */
    fun combatCommand(state: AdventureState, playerId: String, turnId: TurnId, action: DemoAction?, target: Tile?): AdventureState {
        val battle = state.combat ?: return state
        if (state.ending != null || state.turnId != turnId || battle.activeId != playerId || !battle.playerTurn) return state
        val next = if (action == null) battle.endTurn() else battle.execute(action, target, dice).completeExhaustedTurn()
        if (next == battle) return state
        val resolved = state.copy(combat = next).settleCombat(previousLogSize = battle.log.size)
        return recordTransition(state, resolved, "combat", playerId)
    }

    private fun AdventureState.settleCombat(previousLogSize: Int = 0): AdventureState {
        var battle = requireNotNull(combat)
        // Enemy turns run on the authority, never on observing clients.
        while (!battle.finished && battle.active.enemy) battle = battle.enemyTurn(dice)
        var next = copy(combat = battle, players = players.map { player ->
            val fighter = battle.fighters.first { it.id == player.id }
            player.copy(hero = player.hero.copy(health = fighter.hp,
                inventory = player.hero.inventory.filterNot { it == ArchiveDemo.BANDAGE } + List(fighter.bandages) { ArchiveDemo.BANDAGE }))
        })
        battle.log.withIndex().drop(previousLogSize).forEach { (index, message) ->
            next = next.append(EntryKind.SYSTEM, message,
                diceEvents = battle.diceEvents.filter { it.logIndex == index },
                combatEvents = battle.events.filter { it.logIndex == index })
        }
        val encounter = requireNotNull(scenario.encounter)
        val variant = battle.encounterVariantId?.let { id -> encounter.variants.single { it.id == id } }
        if (battle.escortObjective?.phase == EscortPhase.EXPIRED && variant != null && variant.expirationFact !in next.scene.facts) {
            next = next.copy(scene = next.scene.copy(facts = next.scene.facts + variant.expirationFact))
                .append(EntryKind.SYSTEM, scenario.facts.getValue(variant.expirationFact).description)
        }
        if (battle.finished) {
            next = next.copy(combat = null, turnOrder = next.turnOrder.next(players.first().id))
            if (battle.victory && battle.escortObjective?.phase == EscortPhase.ESCAPED && variant != null) {
                return next.copy(scene = next.scene.copy(facts = next.scene.facts + variant.victoryFact))
                    .finish(variant.victoryEnding)
            }
            return if (battle.victory) next.copy(
                players = next.players.map { it.copy(location = encounter.location) },
                scene = next.scene.copy(facts = next.scene.facts + encounter.victoryFact))
                .append(EntryKind.NARRATOR, scenario.facts.getValue(encounter.victoryFact).description)
            else next.finish(encounter.defeatEnding)
        }
        return next.copy(turnOrder = next.turnOrder.next(battle.activeId))
    }

    private fun AdventureState.append(
        kind: EntryKind, text: String, roll: RollResult? = null, actor: Player? = null, speakerNpcId: String? = null,
        diceEvents: List<DiceEvent> = emptyList(),
        combatEvents: List<CombatEvent> = emptyList(),
        speechEligible: Boolean = true,
    ): AdventureState = copy(
        journal = journal + JournalEntry(
            (journal.lastOrNull()?.id ?: 0) + 1, kind, text, roll, actor?.id, actor?.hero?.name, speakerNpcId, diceEvents, combatEvents, speechEligible,
        ),
    )

    companion object {
        const val MAX_ACTION_LENGTH = 500

        fun demoPlayers(scenarioId: String = Lighthouse.ID): List<Player> {
            val scenario = requireNotNull(Scenarios.find(scenarioId))
            return ReadyCharacters.all.take(3).mapIndexed { index, profile -> Player("player-${index + 1}", "Игрок ${index + 1}", profile.hero(scenario), scenario.startLocation) }
        }
    }
}
