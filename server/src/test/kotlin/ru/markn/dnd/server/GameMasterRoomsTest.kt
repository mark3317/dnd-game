package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class GameMasterRoomsTest {
    private suspend fun started(rooms: Rooms): Pair<Rooms.Admission, Rooms.Admission> {
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        return a to b
    }

    private fun submit(a: Rooms.Admission, id: Long = 2) = ClientMessage.Command(id, NetworkAction.SUBMIT,
        turnId = a.connection.updates.value.adventure!!.turnId, text = "Что случилось с маяком?")

    private suspend fun finished(a: Rooms.Admission) = withTimeout(3_000) {
        a.connection.updates.first { it.pendingNarration == null }
    }

    @Test fun acceptanceIsImmediateOtherRoomsProceedAndNoDuplicateRequestIsSent() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<GameMasterDecision>()
        var calls = 0
        val rooms = Rooms(gameMaster = GameMaster { _, _ -> calls++; entered.complete(Unit); response.await() }, aiScope = this)
        val (a, b) = started(rooms)
        val (c, _) = started(rooms)
        val ack = rooms.command(a.connection, submit(a))
        assertNull(ack.error)
        assertNotNull(ack.room.pendingNarration)
        assertEquals(1L, ack.room.adventure!!.turnId.number)
        entered.await()
        assertNotNull(rooms.command(a.connection, submit(a)).error)
        assertNotNull(rooms.command(a.connection, submit(a, 3)).error)
        assertNotNull(rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.PASS,
            turnId = ack.room.adventure!!.turnId)).error)
        assertNull(withTimeout(1_000) { rooms.command(c.connection, ClientMessage.Command(2,
            NetworkAction.PASS, turnId = c.connection.updates.value.adventure!!.turnId)) }.error)
        response.complete(GameMasterDecision(Lighthouse.TALK, "Привод заклинило, но лампа исправна."))
        val done = finished(a)
        assertEquals(1, calls)
        assertEquals(2L, done.adventure!!.turnId.number)
        assertEquals(done, b.connection.updates.value)
        assertEquals(3, done.adventure!!.checksRemaining)
    }

    @Test fun cancellationIsOwnedAndLateResultCannotChangeStateOrNewRequest() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var calls = 0
        val rooms = Rooms(gameMaster = GameMaster { _, _ ->
            if (++calls == 1) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                exited.complete(Unit)
            }
            GameMasterDecision(null, "Помощь хранителя.", SceneActionPlan("Попросить хранителя помочь", null, null,
                listOf(SceneEffect.Fact(Lighthouse.KEEPER_HELPING)), emptyList()))
        }, aiScope = this)
        val (a, b) = started(rooms)
        val ack = rooms.command(a.connection, submit(a))
        entered.await()
        val pending = ack.room.pendingNarration!!
        val cancel = ClientMessage.Command(1, NetworkAction.CANCEL_NARRATION,
            turnId = pending.turnId, narrationId = pending.id)
        assertNotNull(rooms.command(b.connection, cancel).error)
        assertNotNull(rooms.command(a.connection, cancel.copy(requestId = 3, narrationId = "old")).error)
        val cancelled = rooms.command(a.connection, cancel.copy(requestId = 4))
        assertNull(cancelled.error)
        assertNull(cancelled.room.pendingNarration)
        assertEquals(ack.room.adventure, cancelled.room.adventure)
        rooms.command(a.connection, submit(a, 5))
        val done = finished(a)
        release.complete(Unit)
        exited.await()
        yield()
        assertEquals(done.adventure, a.connection.updates.value.adventure)
        assertEquals(2L, done.adventure!!.turnId.number)
    }

    @Test fun timeoutAndProviderErrorLeaveTurnAndJournalUntouched() = runBlocking<Unit> {
        for (hang in listOf(false, true)) {
            val rooms = Rooms(gameMaster = GameMaster { _, _ ->
                if (hang) awaitCancellation() else error("private provider response and key")
            }, aiScope = this, aiTimeoutMillis = 30)
            val (a, _) = started(rooms)
            val original = a.connection.updates.value.adventure
            rooms.command(a.connection, submit(a))
            val done = finished(a)
            assertEquals(original, done.adventure)
            assertNotNull(done.gameMasterError)
            assertFalse(done.gameMasterError!!.contains("private"))
        }
    }

    @Test fun restartInvalidatesRequestAndReconnectPreservesPendingOperation() = runBlocking<Unit> {
        val gate = CompletableDeferred<GameMasterDecision>()
        val rooms = Rooms(gameMaster = GameMaster { _, _ -> gate.await() }, aiScope = this)
        val (a, b) = started(rooms)
        val pending = rooms.command(a.connection, submit(a)).room.pendingNarration
        rooms.disconnect(a.connection)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, a.welcome.resumeToken))
        assertEquals(pending, resumed.welcome.room.pendingNarration)
        // The second player becomes host when A disconnects and can restart while AI is pending.
        val after = rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.RESTART, sessionId = 1))
        assertNull(after.error)
        assertNull(after.room.pendingNarration)
        assertEquals(2L, after.room.adventure!!.sessionId)
        gate.complete(GameMasterDecision(Lighthouse.TALK, "Запоздалый ответ."))
        yield()
        assertEquals(1L, after.room.adventure!!.turnId.number)
        assertEquals(after.room.adventure, resumed.connection.updates.value.adventure)
    }

    @Test fun saturationRejectsBeforeChangingGameAndReleasesSlotAfterFailure() = runBlocking<Unit> {
        val gate = CompletableDeferred<GameMasterDecision>()
        val rooms = Rooms(gameMaster = GameMaster { _, _ -> gate.await() }, aiScope = this, maxAiRequests = 1)
        val (a, _) = started(rooms)
        val (b, _) = started(rooms)
        rooms.command(a.connection, submit(a))
        val rejected = rooms.command(b.connection, submit(b))
        assertNotNull(rejected.error)
        assertNull(rejected.room.pendingNarration)
        assertEquals(1L, rejected.room.adventure!!.turnId.number)
        gate.completeExceptionally(IllegalStateException("private"))
        finished(a)
        yield()
        assertNull(rooms.command(b.connection, submit(b, 3)).error)
        assertNotNull(finished(b).gameMasterError)
    }

    @Test fun invalidPlanIsPublishedAsSafeErrorAndNeverPartiallyAppliesOrSpendsTurn() = runBlocking<Unit> {
        val rooms = Rooms(gameMaster = GameMaster { _, _ ->
            GameMasterDecision(null, "private invalid reply", SceneActionPlan("private intent", null, Lighthouse.ROPE,
                listOf(SceneEffect.Fact(Lighthouse.ROPE_SECURED), SceneEffect.Move(Lighthouse.LANTERN_ROOM)), emptyList()))
        }, aiScope = this)
        val (a, b) = started(rooms)
        val before = a.connection.updates.value.adventure
        rooms.command(a.connection, submit(a))
        val rejected = finished(a)
        assertEquals(before, rejected.adventure)
        assertNotNull(rejected.gameMasterError)
        assertFalse(rejected.gameMasterError!!.contains("private"))
        assertEquals(rejected, b.connection.updates.value)
        assertNull(rejected.pendingNarration)
    }
}
