package ru.markn.dnd.adventure

import kotlin.test.*

class GameMasterDecisionTest {
    private val engine = AdventureEngine(DiceRoller { 10 })

    @Test fun socialRepliesRulesAndClarificationOnlyAppendJournalInBothScenarios() {
        val noDice = AdventureEngine(DiceRoller { error("A comment must not roll dice") })
        for (scenario in listOf(Lighthouse.ID, FloodedArchive.ID)) {
            var state = noDice.newGame(scenarioId = scenario)
            for ((input, reply) in listOf(
                "Ну мы и спасатели, сначала бы пообедать" to "Обед подождёт — приключение уже зовёт!",
                "Как работают проверки?" to "Бросаем d20, добавляем модификатор и сравниваем со сложностью.",
                "Может, сначала осмотримся?" to "Хочешь осмотреть место или пока обсуждаешь план с друзьями?",
            )) {
                val next = noDice.submit(state, state.activePlayer.id, state.turnId, input,
                    GameMasterDecision(null, reply))
                assertEquals(state, next.copy(journal = state.journal))
                assertEquals(state.journal, next.journal.dropLast(2))
                assertEquals(listOf(input, reply), next.journal.takeLast(2).map { it.text })
                assertEquals(listOf(EntryKind.PLAYER, EntryKind.NARRATOR), next.journal.takeLast(2).map { it.kind })
                assertTrue(next.journal.takeLast(2).all { it.playerId == state.activePlayer.id })
                state = next
            }
            val pass = noDice.endTurn(state, state.activePlayer.id, state.turnId)
            assertEquals(state.turnId.number + 1, pass.turnId.number)
            assertEquals(state.checksRemaining, pass.checksRemaining)
        }
    }

    @Test fun humorousInspectionAndNpcConversationStillAdvanceTurn() {
        for ((input, action) in listOf(
            "Осматриваю берег как строгий ревизор" to Lighthouse.LOOK,
            "В шутку прошу хранителя рассказать, что случилось" to Lighthouse.TALK,
        )) {
            val state = engine.newGame()
            val next = engine.submit(state, state.activePlayer.id, state.turnId, input,
                GameMasterDecision(action, "Хранитель кивает в сторону тёмного маяка."))
            assertEquals(state.turnId.number + 1, next.turnId.number)
            assertEquals(state.players, next.players)
            assertEquals(state.scene, next.scene)
            assertEquals(state.checksRemaining, next.checksRemaining)
            assertNull(next.pendingCheck)
        }
    }

    @Test fun gameMasterCanKeepActorOrSelectAnyPartyMemberAndStaleCommandsAreRejected() {
        val state = engine.newGame()
        val actor = state.activePlayer.id
        val continuing = engine.submit(state, actor, state.turnId, "Расспрашиваю хранителя",
            GameMasterDecision(Lighthouse.TALK, "Хранитель показывает на маяк.", nextPlayerId = actor))
        assertEquals(actor, continuing.activePlayer.id)
        assertEquals(state.turnId.number + 1, continuing.turnId.number)
        assertEquals(continuing, engine.submit(continuing, actor, state.turnId, Lighthouse.LOOK.text))
        val third = state.players[2].id
        val selected = engine.submit(continuing, actor, continuing.turnId, "Осматриваю берег",
            GameMasterDecision(Lighthouse.LOOK, "Туман скрывает реку.", nextPlayerId = third))
        assertEquals(third, selected.activePlayer.id)
        assertEquals(state.turnOrder.playerIds, selected.turnOrder.playerIds)
        assertEquals(state.checksRemaining, selected.checksRemaining)
        assertEquals(state.players, selected.players)
        assertEquals(selected, engine.submit(selected, actor, selected.turnId, Lighthouse.LOOK.text))
        val passed = engine.endTurn(selected, third, selected.turnId)
        assertEquals(actor, passed.activePlayer.id)
    }

    @Test fun selectedActorIsPinnedUntilRollAndCancellationKeepsCurrentActor() {
        for (die in listOf(1, 20)) {
            val engine = AdventureEngine(DiceRoller { die })
            val state = engine.newGame()
            val target = state.players[2].id
            val pending = engine.submit(state, state.activePlayer.id, state.turnId, "Поднимаюсь",
                GameMasterDecision(Lighthouse.CLIMB, "Попытка подъёма.", nextPlayerId = target))
            val check = assertNotNull(pending.pendingCheck)
            assertEquals(target, check.nextPlayerId)
            assertEquals(state.activePlayer.id, pending.activePlayer.id)
            assertEquals(state.turnId, pending.turnId)
            val cancelled = engine.cancelCheck(pending, state.activePlayer.id, check.id)
            assertEquals(state.turnOrder, cancelled.turnOrder)
            assertEquals(state.checksRemaining, cancelled.checksRemaining)
            val rolled = engine.roll(pending, state.activePlayer.id, check.id)
            assertEquals(target, rolled.activePlayer.id)
            assertEquals(state.turnId.number + 1, rolled.turnId.number)
            assertEquals(state.checksRemaining - 1, rolled.checksRemaining)
            assertEquals(rolled, engine.roll(rolled, state.activePlayer.id, check.id))
        }
    }

    @Test fun unknownActorAndAttemptToPassByCommentAreRejectedAtomically() {
        val state = engine.newGame()
        for (decision in listOf(
            GameMasterDecision(Lighthouse.LOOK, "Ответ", nextPlayerId = "not-a-player"),
            GameMasterDecision(null, "Шутка", nextPlayerId = state.players[1].id),
        )) {
            assertEquals(state, engine.submit(state, state.activePlayer.id, state.turnId, "Реплика", decision))
        }
        val comment = engine.submit(state, state.activePlayer.id, state.turnId, "Шутка",
            GameMasterDecision(null, "Ответ", nextPlayerId = state.activePlayer.id))
        assertEquals(state, comment.copy(journal = state.journal))
    }

    @Test fun naturalLanguagePreservesOriginalTextAndServerCheck() {
        val state = engine.newGame()
        val next = engine.submit(state, state.activePlayer.id, state.turnId, "Осторожно лезу по мокрой лестнице",
            GameMasterDecision(Lighthouse.CLIMB, "Герой уже наверху и всех спас!"))
        assertEquals(Lighthouse.SHORE, next.activePlayer.location)
        assertEquals(state.turnId, next.turnId)
        assertEquals(3, next.checksRemaining)
        val check = assertNotNull(next.pendingCheck)
        assertEquals(10, check.difficulty)
        assertEquals(Ability.DEXTERITY, check.ability)
        assertTrue(next.journal.any { it.text == "Осторожно лезу по мокрой лестнице" })
        assertFalse(next.journal.any { it.text.contains("всех спас") })
        val rolled = engine.roll(next, next.activePlayer.id, check.id)
        assertEquals(Lighthouse.LANTERN_ROOM, rolled.players.first().location)
        assertEquals(2, rolled.checksRemaining)
        assertEquals(2L, rolled.turnId.number)
    }

    @Test fun modelCannotBypassLocationOrAdvanceClarification() {
        val state = engine.newGame()
        val repair = engine.submit(state, state.activePlayer.id, state.turnId, "Чиню маяк силой мысли",
            GameMasterDecision(Lighthouse.REPAIR, "Готово"))
        assertNull(repair.pendingCheck)
        assertNull(repair.ending)
        assertEquals(state.turnId, repair.turnId)
        val clarify = engine.submit(state, state.activePlayer.id, state.turnId, "Хочу чего-нибудь",
            GameMasterDecision(null, "Что именно ты хочешь осмотреть?"))
        assertEquals(state.turnId, clarify.turnId)
        assertEquals(state.players, clarify.players)
        assertEquals("Что именно ты хочешь осмотреть?", clarify.journal.last().text)
    }

    @Test fun dialogueAdvancesTurnWithoutSpendingChecksAndCannotActForAnotherPlayer() {
        val state = engine.newGame()
        val answer = GameMasterDecision(Lighthouse.TALK, "Хранитель просит подать три вспышки.")
        assertEquals(state, engine.submit(state, state.players[1].id, state.turnId, "Что делать?", answer))
        val next = engine.submit(state, state.activePlayer.id, state.turnId, "Что делать?", answer)
        assertEquals(2L, next.turnId.number)
        assertEquals(3, next.checksRemaining)
        assertTrue(next.journal.any { it.text == answer.narration })
        assertEquals(state.players, next.players)
    }
}
