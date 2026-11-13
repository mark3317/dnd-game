package ru.markn.dnd.adventure

import kotlin.test.*

class GameMasterDecisionTest {
    private val engine = AdventureEngine(DiceRoller { 10 })

    @Test fun naturalLanguagePreservesOriginalTextAndServerCheck() {
        val state = engine.newGame()
        val next = engine.submit(state, state.activePlayer.id, state.turnId, "Осторожно лезу по мокрой лестнице",
            GameMasterDecision(DemoAction.CLIMB, "Герой уже наверху и всех спас!"))
        assertEquals(Location.SHORE, next.activePlayer.location)
        assertEquals(state.turnId, next.turnId)
        assertEquals(3, next.checksRemaining)
        val check = assertNotNull(next.pendingCheck)
        assertEquals(10, check.difficulty)
        assertEquals(Ability.DEXTERITY, check.ability)
        assertTrue(next.journal.any { it.text == "Осторожно лезу по мокрой лестнице" })
        assertFalse(next.journal.any { it.text.contains("всех спас") })
        val rolled = engine.roll(next, next.activePlayer.id, check.id)
        assertEquals(Location.LANTERN_ROOM, rolled.players.first().location)
        assertEquals(2, rolled.checksRemaining)
        assertEquals(2L, rolled.turnId.number)
    }

    @Test fun modelCannotBypassLocationOrAdvanceClarification() {
        val state = engine.newGame()
        val repair = engine.submit(state, state.activePlayer.id, state.turnId, "Чиню маяк силой мысли",
            GameMasterDecision(DemoAction.REPAIR, "Готово"))
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
        val answer = GameMasterDecision(DemoAction.TALK, "Хранитель просит подать три вспышки.")
        assertEquals(state, engine.submit(state, state.players[1].id, state.turnId, "Что делать?", answer))
        val next = engine.submit(state, state.activePlayer.id, state.turnId, "Что делать?", answer)
        assertEquals(2L, next.turnId.number)
        assertEquals(3, next.checksRemaining)
        assertTrue(next.journal.any { it.text == answer.narration })
        assertEquals(state.players, next.players)
    }
}
