package ru.markn.dnd.adventure

import kotlin.random.Random
import ru.markn.dnd.adventure.diagnostics.GameLogEvent
import ru.markn.dnd.adventure.diagnostics.GameLogger

fun interface DiceRoller {
    fun roll(sides: Int): Int
}

/** Local scripted adventure. Commands carry their actor and turn, not network authorization. */
class AdventureEngine(
    private val dice: DiceRoller = DiceRoller { sides -> Random.nextInt(1, sides + 1) },
    private val logger: GameLogger = GameLogger.None,
) {
    fun newGame(sessionId: Long = 1, players: List<Player> = demoPlayers()): AdventureState = AdventureState(
        sessionId = sessionId,
        players = players.toList(),
        turnOrder = TurnOrder(players.map { it.id }),
        journal = listOf(
            JournalEntry(
                id = 1,
                kind = EntryKind.NARRATOR,
                text = "Речной маяк погас. В тумане слышен гудок парома — он идёт прямо к отмели. " +
                    "У двери ждёт встревоженный хранитель. Наверх ведёт мокрая лестница. " +
                    "Можно попытаться вернуть свет или подать сигнал с берега. Действуйте по очереди.",
            ),
        ),
    ).also { state ->
        log("session_started", state, details = mapOf(
            "players" to state.players.size.toString(),
            "queue" to state.turnOrder.playerIds.joinToString(","),
        ))
    }

    fun submit(state: AdventureState, playerId: String, turnId: TurnId, input: String): AdventureState {
        val text = input.trim()
        if (state.ending != null) return rejected(state, "submit", playerId, "adventure_finished")
        if (state.pendingCheck != null) return rejected(state, "submit", playerId, "pending_check")
        if (state.activePlayer.id != playerId) return rejected(state, "submit", playerId, "wrong_player")
        if (state.turnId != turnId) return rejected(state, "submit", playerId, "stale_turn", mapOf(
            "requested_session" to turnId.sessionId.toString(), "requested_turn" to turnId.number.toString(),
        ))
        if (text.isEmpty()) return rejected(state, "submit", playerId, "blank_input")
        if (text.length > MAX_ACTION_LENGTH) return rejected(state, "submit", playerId, "input_too_long")
        val actor = state.activePlayer
        val next = state.append(EntryKind.PLAYER, text, actor = actor)
        val normalized = text.lowercase().trimEnd('.', '!', '?')
        val action = DemoAction.entries.firstOrNull { it.text.lowercase() == normalized }
        val result = when (action) {
            DemoAction.LOOK -> next.append(
                EntryKind.NARRATOR,
                if (actor.location == Location.SHORE) {
                    "С берега видна отмель. Фонарём можно предупредить паром. " +
                        "Если подняться по лестнице, можно осмотреть механизм маяка. Осмотр не расходует запас проверок."
                } else {
                    "Линза цела, но привод заклинило. Инструментами можно освободить шестерню. " +
                        "Через окно также можно подать сигнал фонарём. Осмотр не расходует запас проверок."
                },
                actor = actor,
            ).advanceTurn()
            DemoAction.TALK -> next.append(
                EntryKind.NARRATOR,
                if (actor.location == Location.SHORE) {
                    "«Шестерня заела, но лампа исправна, — говорит хранитель. — " +
                        "Если не успеете починить, подайте три вспышки фонарём: капитан поймёт»."
                } else {
                    "Снизу доносится голос хранителя: «Освободите шестерню! Если не выйдет, подайте три вспышки фонарём»."
                },
                actor = actor,
            ).advanceTurn()
            DemoAction.CLIMB -> if (actor.location == Location.LANTERN_ROOM) {
                next.append(EntryKind.NARRATOR, "Этот герой уже наверху. Можно починить механизм или подать сигнал.", actor = actor)
            } else {
                next.request(DemoAction.CLIMB, Ability.DEXTERITY, 10, "Удержаться на мокрой лестнице и подняться к фонарю.")
            }
            DemoAction.REPAIR -> if (actor.location == Location.SHORE) {
                next.append(EntryKind.NARRATOR, "Механизм наверху. Этому герою сначала нужно подняться к фонарю; другой путь — подать сигнал с берега.", actor = actor)
            } else {
                next.request(DemoAction.REPAIR, Ability.INTELLIGENCE, 12, "Освободить шестерню с помощью инструментов.")
            }
            DemoAction.SIGNAL -> next.request(
                DemoAction.SIGNAL, Ability.WISDOM, 12,
                "Выбрать момент между полосами тумана и подать парому сигнал.",
            )
            null -> next.append(
                EntryKind.NARRATOR,
                "Пробный ведущий понимает действия из подсказок под историей. " +
                    "Выберите подсказку или введите её текст. Ситуация и очередь не изменились.",
                actor = actor,
            )
        }
        return recordTransition(state, result, "submit", playerId, mapOf(
            "action" to (action?.name ?: "UNSUPPORTED"),
            "input_length" to text.length.toString(),
            "outcome" to when {
                action == null -> "unsupported"
                result.turnId == state.turnId && result.pendingCheck == null -> "unavailable"
                else -> "accepted"
            },
        ))
    }

    fun roll(state: AdventureState, playerId: String, checkId: CheckId): AdventureState {
        val reason = state.checkRejectionReason(playerId, checkId)
        if (reason != null) return rejected(state, "roll", playerId, reason, mapOf("requested_check" to checkId.actionId.toString()))
        val check = requireNotNull(state.pendingCheck)
        val actor = state.activePlayer
        val die = try {
            dice.roll(20)
        } catch (failure: Exception) {
            log("dice_failed", state, playerId, mapOf("reason" to "dice_source_exception", "type" to (failure::class.simpleName ?: "Exception")))
            throw failure
        }
        if (die !in 1..20) log("dice_failed", state, playerId, mapOf("reason" to "invalid_result", "die" to die.toString()))
        require(die in 1..20) { "A d20 result must be between 1 and 20" }
        val result = RollResult(check.id, actor.id, check.ability, die, actor.hero.modifier(check.ability), check.difficulty)
        val checks = (state.checksRemaining - 1).coerceAtLeast(0)
        var next = state.copy(pendingCheck = null, checksRemaining = checks)
            .append(EntryKind.CHECK, if (result.succeeded) "Проверка пройдена" else "Проверка не пройдена", result, actor)
        next = when (check.action) {
            DemoAction.CLIMB -> if (result.succeeded) {
                next.updatePlayer(actor.copy(location = Location.LANTERN_ROOM)).append(
                    EntryKind.NARRATOR, "Герой поднимается к линзе. Привод заклинило — инструменты помогут вернуть свет.", actor = actor,
                )
            } else {
                next.updatePlayer(actor.copy(hero = actor.hero.copy(health = (actor.hero.health - 2).coerceAtLeast(0)))).append(
                    EntryKind.NARRATOR, "Нога соскальзывает. Герой успевает ухватиться за перила, но получает ушиб: −2 здоровья. Часть времени потеряна.", actor = actor,
                )
            }
            DemoAction.REPAIR -> if (result.succeeded) next.finish(Ending.LIGHTHOUSE_LIT) else {
                next.append(EntryKind.NARRATOR, "Привод не поддаётся. На следующем ходу можно повторить попытку или подать сигнал фонарём.", actor = actor)
            }
            DemoAction.SIGNAL -> if (result.succeeded) next.finish(Ending.SIGNAL_GIVEN) else {
                next.append(EntryKind.NARRATOR, "Туман поглощает вспышки. Ответа пока нет; группа может повторить сигнал или сменить подход.", actor = actor)
            }
            else -> error("This action does not use a check")
        }
        // Completing the objective on the last remaining check still wins.
        if (next.ending == null && checks == 0) next = next.finish(Ending.FERRY_LOST)
        val resultState = if (next.ending == null) next.advanceTurn() else next
        return recordTransition(state, resultState, "roll", playerId)
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
                "action" to check.action.name,
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
                    "location_before" to previous.location.name,
                    "location_after" to player.location.name,
                ))
            }
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
            log("adventure_finished", after, playerId, mapOf("ending" to after.ending.name))
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

    private fun AdventureState.request(
        action: DemoAction, ability: Ability, difficulty: Int, description: String,
    ): AdventureState = copy(
        pendingCheck = PendingCheck(
            CheckId(sessionId, journal.last().id), activePlayer.id, turnId, action, ability, difficulty, description,
        ),
    )

    private fun AdventureState.updatePlayer(player: Player): AdventureState =
        copy(players = players.map { if (it.id == player.id) player else it })

    private fun AdventureState.advanceTurn(): AdventureState {
        val next = copy(turnOrder = turnOrder.next())
        return next.append(
            EntryKind.TURN,
            "Ход: ${next.activePlayer.name} · ${next.activePlayer.hero.name}. Круг ${next.turnOrder.roundNumber}.",
            actor = next.activePlayer,
        )
    }

    private fun AdventureState.finish(result: Ending): AdventureState =
        copy(ending = result, pendingCheck = null).append(EntryKind.NARRATOR, result.description)

    private fun AdventureState.append(
        kind: EntryKind, text: String, roll: RollResult? = null, actor: Player? = null,
    ): AdventureState = copy(
        journal = journal + JournalEntry(
            (journal.lastOrNull()?.id ?: 0) + 1, kind, text, roll, actor?.id, actor?.hero?.name,
        ),
    )

    companion object {
        const val MAX_ACTION_LENGTH = 500

        fun demoPlayers(): List<Player> {
            val rin = Hero(
                "Рин", "Следопыт", 12, 12,
                mapOf(
                    Ability.STRENGTH to 1, Ability.DEXTERITY to 3, Ability.CONSTITUTION to 2,
                    Ability.INTELLIGENCE to 1, Ability.WISDOM to 2, Ability.CHARISMA to 0,
                ),
                listOf("Походный фонарь", "Верёвка", "Набор инструментов"),
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
            return listOf(Player("player-1", "Игрок 1", rin), Player("player-2", "Игрок 2", mira), Player("player-3", "Игрок 3", bran))
        }
    }
}