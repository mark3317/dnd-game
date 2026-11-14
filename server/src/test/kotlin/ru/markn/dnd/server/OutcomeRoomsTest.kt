package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class OutcomeRoomsTest {
    private class Narrator(
        val reply: suspend (OutcomeNarration) -> String,
        val action: (AdventureState) -> GameMasterDecision = { state ->
            GameMasterDecision(Lighthouse.CLIMB, "Подъём", nextPlayerId = state.players.first { it.id != state.activePlayer.id }.id)
        },
    ) : GameMaster, OutcomeNarrator {
        var proposals = 0
        val outcomes = mutableListOf<OutcomeNarration>()
        override suspend fun respond(state: AdventureState, input: String): GameMasterDecision {
            proposals++
            return action(state)
        }
        override suspend fun narrate(outcome: OutcomeNarration): String {
            outcomes += outcome
            return reply(outcome)
        }
    }

    private suspend fun start(rooms: Rooms): List<Rooms.Admission> {
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        return listOf(a, b)
    }
    private suspend fun ready(a: Rooms.Admission) = withTimeout(3_000) {
        a.connection.updates.first { it.pendingNarration == null }
    }
    private suspend fun propose(rooms: Rooms, a: Rooms.Admission, id: Long = 2) {
        val state = a.connection.updates.value.adventure!!
        assertNull(rooms.command(a.connection, ClientMessage.Command(id, NetworkAction.SUBMIT,
            turnId = state.turnId, text = "Пробую подняться")).error)
        ready(a)
    }

    @Test fun rollCommitsBeforeNarrationAndReconnectDoesNotRepeatEitherRequest() = runBlocking<Unit> {
        val response = CompletableDeferred<String>()
        var diceCalls = 0
        val narrator = Narrator({ response.await() })
        val rooms = Rooms(narrator, this) { AdventureEngine(DiceRoller { diceCalls++; 1 }) }
        val (a, b) = start(rooms)
        propose(rooms, a)
        val check = a.connection.updates.value.adventure!!.pendingCheck!!
        val rolled = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id))
        assertNull(rolled.error)
        val committed = rolled.room.adventure!!
        assertEquals(2, committed.checksRemaining)
        assertNull(committed.pendingCheck)
        assertEquals(b.connection.playerId, committed.activePlayer.id)
        assertEquals(NarrationKind.REACTION, rolled.room.pendingNarration!!.kind)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, a.welcome.resumeToken))
        assertEquals(rolled.room.pendingNarration, resumed.welcome.room.pendingNarration)
        response.complete("Нога соскальзывает, но герой удерживается у подножия. Мокрая лестница ждёт нового подхода.")
        val done = ready(resumed)
        assertEquals(committed, done.adventure!!.copy(journal = committed.journal))
        assertEquals(done, b.connection.updates.value)
        assertEquals(1, diceCalls)
        assertEquals(1, narrator.proposals)
        assertEquals(1, narrator.outcomes.size)
        assertEquals(check.playerId, narrator.outcomes.single().activity.playerId)
        assertFalse(narrator.outcomes.single().activity.roll!!.succeeded)
        assertEquals(check.playerId, done.adventure!!.journal.last().playerId)
        assertNotNull(rooms.command(resumed.connection, ClientMessage.Command(1, NetworkAction.ROLL, checkId = check.id)).error)
        assertEquals(1, diceCalls)
    }

    @Test fun winningRollRequestsOneEpilogueInsteadOfSeparateReaction() = runBlocking<Unit> {
        val narrator = Narrator({ "Свет пробивается сквозь туман. Паром спасён, и герои могут перевести дух." },
            { state -> GameMasterDecision(Lighthouse.SIGNAL, "Сигнал", nextPlayerId = state.activePlayer.id) })
        val rooms = Rooms(narrator, this) { AdventureEngine(DiceRoller { 20 }) }
        val (a, b) = start(rooms)
        propose(rooms, a)
        val check = a.connection.updates.value.adventure!!.pendingCheck!!
        val rolled = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id))
        assertEquals(NarrationKind.EPILOGUE, rolled.room.pendingNarration!!.kind)
        assertEquals(Lighthouse.SIGNAL_GIVEN, rolled.room.adventure!!.ending)
        val committed = rolled.room.adventure!!
        val done = ready(a)
        assertEquals(committed, done.adventure!!.copy(journal = committed.journal))
        assertEquals(done, b.connection.updates.value)
        assertTrue(narrator.outcomes.single().epilogue)
        assertEquals(1, narrator.outcomes.single().history.size)
        assertEquals(setOf(Lighthouse.FERRY_WARNED), narrator.outcomes.single().activity.factsAdded)
    }

    @Test fun losingFinalRollKeepsAllConfirmedContributionsAndDoesNotAskForActionAgain() = runBlocking<Unit> {
        val narrator = Narrator({ if (it.epilogue) "Туман скрыл паром. Герои сделали всё, что смогли, но времени не хватило." else "Мокрая лестница не поддалась." })
        val rooms = Rooms(narrator, this) { AdventureEngine(DiceRoller { 1 }) }
        val party = start(rooms)
        var id = 1L
        repeat(3) {
            val state = party[0].connection.updates.value.adventure!!
            val actor = party.single { it.connection.playerId == state.activePlayer.id }
            propose(rooms, actor, ++id)
            val check = actor.connection.updates.value.adventure!!.pendingCheck!!
            rooms.command(actor.connection, ClientMessage.Command(++id, NetworkAction.ROLL, checkId = check.id))
            ready(actor)
        }
        val done = party[0].connection.updates.value.adventure!!
        assertEquals(Lighthouse.FERRY_LOST, done.ending)
        assertEquals(0, done.checksRemaining)
        assertEquals(3, narrator.outcomes.size)
        assertEquals(1, narrator.outcomes.count { it.epilogue })
        assertEquals(3, narrator.outcomes.last().history.size)
        assertEquals(2, narrator.outcomes.last().history.map { it.playerId }.distinct().size)
        assertTrue(narrator.outcomes.last().history.all { it.roll?.succeeded == false })
    }

    @Test fun cancelAndRestartInvalidateLateDescriptionWithoutUndoingRoll() = runBlocking<Unit> {
        for (restart in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val narrator = Narrator({
                withContext(NonCancellable) { entered.complete(Unit); release.await(); exited.complete(Unit) }
                "Запоздалое описание"
            })
            val rooms = Rooms(narrator, this) { AdventureEngine(DiceRoller { 1 }) }
            val (a, b) = start(rooms)
            propose(rooms, a)
            val check = a.connection.updates.value.adventure!!.pendingCheck!!
            val rolled = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id)).room
            entered.await()
            val pending = rolled.pendingNarration!!
            assertNotNull(rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.CANCEL_NARRATION,
                turnId = pending.turnId, narrationId = pending.id)).error)
            val stopped = if (restart) rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.RESTART, sessionId = 1))
            else rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.CANCEL_NARRATION,
                turnId = pending.turnId, narrationId = pending.id))
            assertNull(stopped.error)
            if (!restart) assertEquals(rolled.adventure, stopped.room.adventure)
            else assertEquals(2L, stopped.room.adventure!!.sessionId)
            release.complete(Unit); exited.await(); yield()
            assertEquals(stopped.room.adventure, a.connection.updates.value.adventure)
            assertFalse(a.connection.updates.value.adventure!!.journal.any { it.text == "Запоздалое описание" })
        }
    }

    @Test fun failureOrTimeoutOfNarrationNeverRollsBackOrExposesProviderMessage() = runBlocking<Unit> {
        for (hang in listOf(false, true)) {
            val narrator = Narrator({ if (hang) awaitCancellation() else error("private provider body and key") })
            val rooms = Rooms(narrator, this, aiTimeoutMillis = 100) { AdventureEngine(DiceRoller { 1 }) }
            val (a, _) = start(rooms)
            propose(rooms, a)
            val check = a.connection.updates.value.adventure!!.pendingCheck!!
            val committed = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id)).room.adventure
            val done = ready(a)
            assertEquals(committed, done.adventure)
            assertNotNull(done.gameMasterError)
            assertFalse(done.gameMasterError!!.contains("private"))
            assertEquals(2, done.adventure!!.checksRemaining)
        }
    }

    @Test fun saturatedNarrationCapacityDoesNotRejectOrDelayAConfirmedRoll() = runBlocking<Unit> {
        val response = CompletableDeferred<String>()
        val narrator = Narrator({ response.await() })
        val rooms = Rooms(narrator, this, maxAiRequests = 1) { AdventureEngine(DiceRoller { 1 }) }
        val (a, _) = start(rooms)
        val (b, _) = start(rooms)
        propose(rooms, a); yield()
        propose(rooms, b); yield()
        val checkA = a.connection.updates.value.adventure!!.pendingCheck!!
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = checkA.id))
        val checkB = b.connection.updates.value.adventure!!.pendingCheck!!
        val committed = rooms.command(b.connection, ClientMessage.Command(3, NetworkAction.ROLL, checkId = checkB.id))
        assertNull(committed.error)
        assertNull(committed.room.pendingNarration)
        assertNotNull(committed.room.gameMasterError)
        assertEquals(2, committed.room.adventure!!.checksRemaining)
        response.complete("Неудачная попытка оставила героя у подножия.")
        ready(a)
    }
}
