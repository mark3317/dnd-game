package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class FastStoryRoomsTest {
    @Test fun authoredMechanicalButtonsAndTheirRollsDoNotCallModel() = runBlocking<Unit> {
        var calls = 0
        val gm = object : GameMaster, OutcomeNarrator {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision { calls++; error("No model needed") }
            override suspend fun narrate(outcome: OutcomeNarration): String { calls++; error("No model needed") }
        }
        val rooms = Rooms(gm, this, presentationEnabled = false) { AdventureEngine(DiceRoller { 1 }) }
        val a = rooms.join(ClientMessage.Join("A"))
        rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START)).room.adventure!!
        val pending = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT, turnId = start.turnId, text = Lighthouse.CLIMB.text))
        assertNull(pending.error)
        assertNull(pending.room.pendingNarration)
        val check = pending.room.adventure!!.pendingCheck!!
        val done = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id))
        assertNull(done.error)
        assertNull(done.room.pendingNarration)
        assertEquals(2, done.room.adventure!!.checksRemaining)
        assertEquals(0, calls)
    }

    @Test fun nextPlayerWaitsForFreePlanReactionWithoutDiscardingSpeech() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gm = object : GameMaster, OutcomeNarrator {
            override suspend fun respond(state: AdventureState, input: String) = GameMasterDecision(null, "План",
                SceneActionPlan("Подняться", ProposedCheck(Ability.DEXTERITY, 10), null,
                    listOf(SceneEffect.Move(Lighthouse.LANTERN_ROOM)), emptyList()), nextPlayerId = state.players[1].id)
            override suspend fun narrate(outcome: OutcomeNarration): String {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return "Запоздалая речь"
            }
        }
        val rooms = Rooms(gm, this, presentationEnabled = false) { AdventureEngine(DiceRoller { 1 }) }
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START)).room.adventure!!
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT, turnId = start.turnId, text = "Пытаюсь подняться иным способом"))
        val prepared = withTimeout(3_000) { a.connection.updates.first { it.adventure?.pendingCheck != null } }
        val rolled = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = prepared.adventure!!.pendingCheck!!.id))
        entered.await()
        val continued = rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.PASS, turnId = rolled.room.adventure!!.turnId))
        assertNotNull(continued.error)
        assertNotNull(continued.room.pendingNarration)
        release.complete(Unit)
        val finished = withTimeout(3_000) { b.connection.updates.first { it.pendingNarration == null } }
        assertTrue(finished.adventure!!.journal.any { it.text == "Запоздалая речь" })
    }
}
