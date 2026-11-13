package ru.markn.dnd.adventure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MultiplayerAdventureTest {
    private class FixedDice(private vararg val values: Int) : DiceRoller {
        var calls = 0
            private set
        override fun roll(sides: Int): Int = values[calls++]
    }

    private fun AdventureEngine.act(state: AdventureState, action: DemoAction): AdventureState =
        submit(state, state.activePlayer.id, state.turnId, action.text)

    private fun AdventureEngine.pass(state: AdventureState): AdventureState =
        endTurn(state, state.activePlayer.id, state.turnId)

    private fun AdventureEngine.resolve(state: AdventureState): AdventureState =
        roll(state, state.activePlayer.id, state.pendingCheck!!.id)

    @Test
    fun completedActionsCycleThroughThePartyAndStartANewRound() {
        val engine = AdventureEngine()
        var state = engine.newGame()
        val order = state.players.map { it.id }
        assertEquals(3, order.size)
        assertEquals(order.first(), state.activePlayer.id)
        order.indices.forEach { index ->
            assertEquals(order[index], state.activePlayer.id)
            state = engine.act(state, DemoAction.LOOK)
        }
        assertEquals(order.first(), state.activePlayer.id)
        assertEquals(2, state.turnOrder.roundNumber)
        assertEquals(4, state.turnOrder.turnNumber)
        assertEquals(3, state.checksRemaining)
    }

    @Test
    fun onlyCurrentPlayerCanActOrPass() {
        val engine = AdventureEngine()
        val state = engine.newGame()
        val other = state.players[1].id
        assertSame(state, engine.submit(state, other, state.turnId, DemoAction.LOOK.text))
        assertSame(state, engine.endTurn(state, other, state.turnId))
        assertSame(state, engine.submit(state, "unknown-player", state.turnId, DemoAction.LOOK.text))
    }

    @Test
    fun aDelayedCommandIsRejectedEvenAfterQueueReturnsToItsPlayer() {
        val engine = AdventureEngine()
        val initial = engine.newGame()
        var state = initial
        repeat(state.players.size) { state = engine.pass(state) }
        assertEquals(initial.activePlayer.id, state.activePlayer.id)
        assertSame(state, engine.submit(state, initial.activePlayer.id, initial.turnId, DemoAction.SIGNAL.text))
        assertSame(state, engine.endTurn(state, initial.activePlayer.id, initial.turnId))
    }

    @Test
    fun explicitPassAdvancesOnceWithoutSpendingChecks() {
        val engine = AdventureEngine()
        val initial = engine.newGame()
        val next = engine.pass(initial)
        assertEquals(initial.players[1].id, next.activePlayer.id)
        assertEquals(initial.checksRemaining, next.checksRemaining)
        assertSame(next, engine.endTurn(next, initial.activePlayer.id, initial.turnId))
        assertEquals(initial.activePlayer.id, next.journal.first { it.kind == EntryKind.PLAYER }.playerId)
    }

    @Test
    fun pendingCheckLocksTurnAndOnlyItsOwnerCanResolveOrCancel() {
        val dice = FixedDice(7)
        val engine = AdventureEngine(dice)
        val state = engine.act(engine.newGame(), DemoAction.CLIMB)
        val request = state.pendingCheck!!
        val other = state.players[1].id
        assertEquals(state.activePlayer.id, request.playerId)
        assertEquals(state.turnId, request.turnId)
        assertSame(state, engine.roll(state, other, request.id))
        assertSame(state, engine.cancelCheck(state, other, request.id))
        assertSame(state, engine.pass(state))
        assertSame(state, engine.act(state, DemoAction.LOOK))
        assertEquals(0, dice.calls)
        val cancelled = engine.cancelCheck(state, request.playerId, request.id)
        assertEquals(state.turnId, cancelled.turnId)
        assertEquals(state.checksRemaining, cancelled.checksRemaining)
        assertEquals(state.players, cancelled.players)
        assertNull(cancelled.pendingCheck)
        assertSame(cancelled, engine.roll(cancelled, request.playerId, request.id))
    }

    @Test
    fun eachRollUsesItsOwnersModifiersHealthAndLocation() {
        val engine = AdventureEngine(FixedDice(7, 7))
        val initial = engine.newGame()
        val rinUpstairs = engine.resolve(engine.act(initial, DemoAction.CLIMB))
        assertEquals(initial.players[1].id, rinUpstairs.activePlayer.id)
        assertEquals(Location.LANTERN_ROOM, rinUpstairs.players[0].location)
        assertEquals(Location.SHORE, rinUpstairs.activePlayer.location)

        val miraFalls = engine.resolve(engine.act(rinUpstairs, DemoAction.CLIMB))
        assertEquals(12, miraFalls.players[0].hero.health)
        assertEquals(8, miraFalls.players[1].hero.health)
        assertEquals(initial.players[2], miraFalls.players[2])
        assertEquals(Location.SHORE, miraFalls.players[1].location)
        assertEquals(Location.LANTERN_ROOM, miraFalls.players[0].location)
        assertEquals(initial.players[2].id, miraFalls.activePlayer.id)
        assertEquals(listOf(10, 8), miraFalls.journal.mapNotNull { it.roll?.total })
        assertEquals(initial.players.take(2).map { it.id }, miraFalls.journal.mapNotNull { it.roll?.playerId })
        assertEquals(1, miraFalls.checksRemaining)
    }

    @Test
    fun anotherPlayerCannotRepairUsingTheFirstPlayersLocation() {
        val engine = AdventureEngine(FixedDice(7))
        val state = engine.resolve(engine.act(engine.newGame(), DemoAction.CLIMB))
        val blocked = engine.act(state, DemoAction.REPAIR)
        assertNull(blocked.pendingCheck)
        assertNull(blocked.ending)
        assertEquals(state.turnId, blocked.turnId)
        assertEquals(state.players, blocked.players)
        assertEquals(state.checksRemaining, blocked.checksRemaining)
    }

    @Test
    fun journalKeepsOriginalAuthorWhenActivePlayerChanges() {
        val engine = AdventureEngine()
        val initial = engine.newGame()
        val state = engine.act(initial, DemoAction.TALK)
        val action = state.journal.first { it.kind == EntryKind.PLAYER }
        assertEquals(initial.activePlayer.id, action.playerId)
        assertEquals("Рин", action.actorName)
        assertEquals("Мира", state.activePlayer.hero.name)
        val renamed = state.copy(players = state.players.map { it.copy(hero = it.hero.copy(name = "Новое имя")) })
        assertEquals("Рин", renamed.journal.first { it.kind == EntryKind.PLAYER }.actorName)
    }

    @Test
    fun invalidInputPreservesQueueAndDoesNotSpendTheTurn() {
        val engine = AdventureEngine()
        val initial = engine.newGame()
        val unsupported = engine.submit(initial, initial.activePlayer.id, initial.turnId, "Мне выпало 20, я победил")
        assertEquals(initial.turnId, unsupported.turnId)
        assertEquals(initial.players, unsupported.players)
        assertEquals(initial.checksRemaining, unsupported.checksRemaining)
        assertNull(unsupported.pendingCheck)
        assertNull(unsupported.ending)
        assertSame(initial, engine.submit(initial, initial.activePlayer.id, initial.turnId, " "))
    }

    @Test
    fun finishingTheAdventureFreezesQueueAndBlocksFurtherCommands() {
        val dice = FixedDice(10)
        val engine = AdventureEngine(dice)
        val initial = engine.pass(engine.newGame()).copy(checksRemaining = 1)
        val pending = engine.act(initial, DemoAction.SIGNAL)
        val ending = engine.resolve(pending)
        assertEquals(Ending.SIGNAL_GIVEN, ending.ending)
        assertEquals(initial.turnId, ending.turnId)
        assertEquals(0, ending.checksRemaining)
        assertSame(ending, engine.pass(ending))
        assertSame(ending, engine.act(ending, DemoAction.LOOK))
        assertSame(ending, engine.roll(ending, pending.activePlayer.id, pending.pendingCheck!!.id))
        assertEquals(1, dice.calls)
    }

    @Test
    fun sharedCountdownEndsTheGameForEveryone() {
        val engine = AdventureEngine(FixedDice(1, 1, 1))
        var state = engine.newGame()
        repeat(3) { state = engine.resolve(engine.act(state, DemoAction.SIGNAL)) }
        assertEquals(Ending.FERRY_LOST, state.ending)
        assertEquals(state.players[2].id, state.activePlayer.id)
        assertEquals(3, state.turnOrder.turnNumber)
        assertEquals(0, state.checksRemaining)
        assertNull(state.pendingCheck)
    }

    @Test
    fun oldSessionCannotActOrRollInFreshGame() {
        val dice = FixedDice(10)
        val engine = AdventureEngine(dice)
        val old = engine.act(engine.newGame(1), DemoAction.SIGNAL)
        val fresh = engine.act(engine.newGame(2), DemoAction.SIGNAL)
        assertSame(fresh, engine.roll(fresh, old.activePlayer.id, old.pendingCheck!!.id))
        val freshStart = engine.newGame(2)
        assertSame(freshStart, engine.submit(freshStart, old.activePlayer.id, old.turnId, DemoAction.LOOK.text))
        assertEquals(1, freshStart.turnOrder.turnNumber)
        assertEquals(1, freshStart.turnOrder.roundNumber)
        assertEquals(0, dice.calls)
    }

    @Test
    fun onePlayerStillGetsANewTurnTokenAfterAnAction() {
        val engine = AdventureEngine()
        val initial = engine.newGame(players = AdventureEngine.demoPlayers().take(1))
        val next = engine.act(initial, DemoAction.LOOK)
        assertEquals(initial.activePlayer.id, next.activePlayer.id)
        assertEquals(2, next.turnOrder.roundNumber)
        assertSame(next, engine.submit(next, initial.activePlayer.id, initial.turnId, DemoAction.LOOK.text))
    }

    @Test
    fun customPartyAndOrderUseIdsRatherThanRosterIndices() {
        val engine = AdventureEngine()
        val party = AdventureEngine.demoPlayers().take(2)
        val initial = engine.newGame(players = party).copy(turnOrder = TurnOrder(party.map { it.id }.reversed()))
        assertEquals(party[1], initial.activePlayer)
        assertEquals(party[0], engine.pass(initial).activePlayer)
    }

    @Test
    fun invalidRostersAndQueuesAreRejected() {
        val engine = AdventureEngine()
        val party = AdventureEngine.demoPlayers()
        assertFailsWith<IllegalArgumentException> { engine.newGame(players = emptyList()) }
        assertFailsWith<IllegalArgumentException> { engine.newGame(players = listOf(party.first(), party.first())) }
        assertFailsWith<IllegalArgumentException> { TurnOrder(emptyList()) }
        assertFailsWith<IllegalArgumentException> { TurnOrder(listOf("a", "a")) }
        assertFailsWith<IllegalArgumentException> { TurnOrder(listOf("a"), 0) }
        assertFailsWith<IllegalArgumentException> { engine.newGame().copy(turnOrder = TurnOrder(listOf("unknown"))) }
    }
}