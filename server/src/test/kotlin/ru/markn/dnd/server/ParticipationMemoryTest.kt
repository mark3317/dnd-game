package ru.markn.dnd.server

import kotlin.test.*
import kotlinx.serialization.json.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.NetworkAction

class ParticipationMemoryTest {
    private val engine = AdventureEngine(DiceRoller { 1 })
    private val memory = ParticipationMemory()
    private var state = engine.newGame()
    init { memory.reset(state) }
    private fun snapshot() = memory.snapshot(state, state.players.map { it.id }.toSet())
    private fun submit(decision: GameMasterDecision, input: String = "Реплика") {
        val old = state
        state = engine.submit(old, old.activePlayer.id, old.turnId, input, decision)
        memory.record(old, state, NetworkAction.SUBMIT)
    }

    @Test fun countsCompletedNarrativeActionsAndKeepsMemoryBeyondRecentJournal() {
        val actor = state.activePlayer.id
        repeat(30) { submit(GameMasterDecision(Lighthouse.LOOK, "Берег скрыт туманом", nextPlayerId = actor)) }
        state = state.copy(journal = state.journal.takeLast(16))
        val result = snapshot()
        assertEquals(30L, result.completedActions)
        assertEquals(30L, result.currentFocusActions)
        assertEquals(12, result.recentActors.size)
        assertTrue(result.recentActors.all { it == actor })
        assertEquals(30L, result.players[0].completedActions)
        assertEquals(0L, result.players[0].actionsSinceLastAction)
        assertNull(result.players[2].lastActionIndex)
        assertEquals(30L, result.players[2].actionsSinceLastAction)
        assertEquals(30L, result.players[2].actionsSinceLastFocus)
    }

    @Test fun commentsUnavailableActionsAndCancelledChecksDoNotCountAsActions() {
        val actor = state.activePlayer.id
        submit(GameMasterDecision(null, "Шутка", nextPlayerId = actor))
        submit(GameMasterDecision(Lighthouse.REPAIR, "Готово", nextPlayerId = actor))
        submit(GameMasterDecision(Lighthouse.CLIMB, "Попытка", nextPlayerId = actor))
        val check = assertNotNull(state.pendingCheck)
        assertEquals(0L, snapshot().completedActions)
        val old = state
        state = engine.cancelCheck(old, actor, check.id)
        memory.record(old, state, NetworkAction.CANCEL)
        assertEquals(0L, snapshot().completedActions)
        assertEquals(0L, snapshot().currentFocusActions)
    }

    @Test fun failedCheckCountsOnceAfterRollAndNotAgainForStaleRollOrDescription() {
        val actor = state.activePlayer.id
        submit(GameMasterDecision(Lighthouse.CLIMB, "Попытка", nextPlayerId = actor))
        val check = state.pendingCheck!!
        val pending = state
        state = engine.roll(pending, actor, check.id)
        memory.record(pending, state, NetworkAction.ROLL)
        assertEquals(1L, snapshot().completedActions)
        assertEquals(1L, snapshot().players[0].rolls)
        val rolled = state
        state = engine.roll(rolled, actor, check.id)
        memory.record(rolled, state, NetworkAction.ROLL)
        val described = state.copy(journal = state.journal + JournalEntry(100, EntryKind.NARRATOR, "Реакция ведущего"))
        memory.record(state, described, NetworkAction.SUBMIT)
        state = described
        assertEquals(1L, snapshot().completedActions)
    }

    @Test fun arbitraryHandoffAndManualPassUpdateFocusWithoutInventingActions() {
        val first = state.players[0].id
        val third = state.players[2].id
        submit(GameMasterDecision(Lighthouse.TALK, "Ответ", nextPlayerId = third))
        assertEquals(third, snapshot().currentPlayerId)
        assertEquals(0L, snapshot().currentFocusActions)
        assertEquals(1L, snapshot().players[2].focusVisits)
        assertEquals(0L, snapshot().players[2].completedActions)
        val frozen = snapshot()
        submit(GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = third))
        submit(GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = third))
        assertEquals(2L, snapshot().currentFocusActions)
        assertEquals(2L, snapshot().players[0].actionsSinceLastAction)
        assertEquals(1L, frozen.completedActions)
        val old = state
        state = engine.endTurn(old, third, old.turnId)
        memory.record(old, state, NetworkAction.PASS)
        assertEquals(first, state.activePlayer.id)
        assertEquals(3L, snapshot().completedActions)
        assertEquals(0L, snapshot().currentFocusActions)
        assertEquals(2L, snapshot().players[0].focusVisits)
    }

    @Test fun reconnectChangesAvailabilityOnlyAndRestartResetsHistory() {
        val actor = state.activePlayer.id
        submit(GameMasterDecision(Lighthouse.LOOK, "Ответ", nextPlayerId = actor))
        val connected = snapshot()
        val offline = memory.snapshot(state, setOf(actor))
        assertFalse(offline.players[1].online)
        assertTrue(offline.toJson().getValue("not_yet_acted_online_player_ids").jsonArray.isEmpty())
        assertTrue(offline.toJson().getValue("longest_waiting_online_player_ids").jsonArray.isEmpty())
        assertEquals(connected.completedActions, offline.completedActions)
        assertEquals(connected, snapshot())
        state = engine.newGame(sessionId = 2)
        memory.reset(state)
        assertEquals(0L, snapshot().completedActions)
        assertTrue(snapshot().recentActors.isEmpty())
        assertTrue(snapshot().players.all { it.completedActions == 0L && it.lastActionIndex == null })
    }

    @Test fun highlightsUnusedOnlinePlayersWithoutSelectingNextActorOrSortingTheParty() {
        val actor = state.activePlayer.id
        repeat(3) { submit(GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = actor)) }
        val data = memory.snapshot(state, setOf(actor, state.players[2].id)).toJson()
        assertEquals(listOf(JsonPrimitive(state.players[2].id)), data.getValue("not_yet_acted_online_player_ids").jsonArray.toList())
        assertEquals(listOf(JsonPrimitive(state.players[2].id)), data.getValue("longest_waiting_online_player_ids").jsonArray.toList())
        assertEquals(actor, state.activePlayer.id)
        assertEquals(state.players.map { it.id }, snapshot().players.map { it.playerId })
    }

    @Test fun snapshotReportsRealLocationsWithoutMovingOrSelectingPlayers() {
        val successfulEngine = AdventureEngine(DiceRoller { 20 })
        val old = state
        val pending = successfulEngine.submit(old, old.activePlayer.id, old.turnId, Lighthouse.CLIMB.text)
        state = successfulEngine.roll(pending, old.activePlayer.id, pending.pendingCheck!!.id)
        memory.record(pending, state, NetworkAction.ROLL)
        assertEquals(state.players[1].id, snapshot().currentPlayerId)
        assertFalse(snapshot().players[0].sameLocationAsActive)
        assertTrue(snapshot().players[2].sameLocationAsActive)
        assertEquals(Lighthouse.LANTERN_ROOM, state.players[0].location)
    }
}
