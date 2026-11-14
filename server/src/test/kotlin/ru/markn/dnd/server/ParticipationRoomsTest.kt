package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class ParticipationRoomsTest {
    @Test fun cancelledModelReplyAndItsLateResultNeverBecomeParticipation() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<ParticipationSnapshot>()
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision = error("Missing snapshot")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot): GameMasterDecision {
                snapshots += participation
                if (input == "delayed") withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); exited.complete(Unit)
                }
                return GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = state.activePlayer.id)
            }
        }
        val rooms = Rooms(gm, this)
        val a = rooms.join(ClientMessage.Join("A"))
        rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        val state = a.connection.updates.value.adventure!!
        val pending = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT,
            turnId = state.turnId, text = "delayed")).room.pendingNarration!!
        entered.await()
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.CANCEL_NARRATION,
            turnId = pending.turnId, narrationId = pending.id))
        rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT, turnId = state.turnId, text = "look"))
        val acted = withTimeout(3_000) { a.connection.updates.first { it.pendingNarration == null } }
        assertEquals(0L, snapshots.last().completedActions)
        release.complete(Unit); exited.await(); yield()
        assertEquals(acted.adventure, a.connection.updates.value.adventure)
        rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.SUBMIT,
            turnId = acted.adventure!!.turnId, text = "look"))
        withTimeout(3_000) { a.connection.updates.first { it.pendingNarration == null } }
        assertEquals(1L, snapshots.last().completedActions)
    }

    @Test fun roomSuppliesFrozenFullSessionMemoryAndPreservesItAcrossCancellationAndReconnect() = runBlocking<Unit> {
        val snapshots = mutableListOf<ParticipationSnapshot>()
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision =
                error("The room must supply participation memory")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot): GameMasterDecision {
                snapshots += participation
                return when (input) {
                    "joke" -> GameMasterDecision(null, "Комментарий", nextPlayerId = state.activePlayer.id)
                    "check" -> GameMasterDecision(Lighthouse.CLIMB, "Подъём", nextPlayerId = state.players[2].id)
                    "invalid" -> GameMasterDecision(Lighthouse.LOOK, "Ответ", nextPlayerId = "unknown")
                    else -> GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = state.activePlayer.id)
                }
            }
        }
        val rooms = Rooms(gm, this) { AdventureEngine(DiceRoller { 20 }) }
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        val c = rooms.join(ClientMessage.Join("C", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        var request = 1L
        suspend fun submit(actor: Rooms.Admission, text: String): RoomView {
            val state = actor.connection.updates.value.adventure!!
            assertNull(rooms.command(actor.connection, ClientMessage.Command(++request, NetworkAction.SUBMIT,
                turnId = state.turnId, text = text)).error)
            return withTimeout(3_000) { actor.connection.updates.first { it.pendingNarration == null } }
        }
        submit(a, "look")
        assertEquals(0L, snapshots.last().completedActions)
        submit(a, "joke")
        assertEquals(1L, snapshots.last().completedActions)
        var pending = submit(a, "check").adventure!!.pendingCheck!!
        rooms.command(a.connection, ClientMessage.Command(++request, NetworkAction.CANCEL, checkId = pending.id))
        pending = submit(a, "check").adventure!!.pendingCheck!!
        assertEquals(1L, snapshots.last().completedActions)
        rooms.command(a.connection, ClientMessage.Command(++request, NetworkAction.ROLL, checkId = pending.id))
        submit(c, "look")
        val atHandoff = snapshots.last()
        assertEquals(2L, atHandoff.completedActions)
        assertEquals(c.connection.playerId, atHandoff.currentPlayerId)
        assertEquals(0L, atHandoff.currentFocusActions)
        assertEquals(2L, atHandoff.players[0].completedActions)
        assertEquals(1L, atHandoff.players[0].rolls)
        assertFalse(atHandoff.players[0].sameLocationAsActive)
        assertNull(atHandoff.players[1].lastActionIndex)
        rooms.disconnect(b.connection)
        submit(c, "joke")
        assertEquals(3L, snapshots.last().completedActions)
        assertFalse(snapshots.last().players[1].online)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, b.welcome.resumeToken))
        submit(c, "invalid")
        assertNotNull(c.connection.updates.value.gameMasterError)
        submit(c, "look")
        assertTrue(snapshots.last().players[1].online)
        assertEquals(3L, snapshots.last().completedActions)
        assertEquals(0L, snapshots.first().completedActions)
        assertEquals(0L, snapshots.first().players[0].completedActions)
        assertEquals(resumed.connection.playerId, snapshots.last().players[1].playerId)
        rooms.command(a.connection, ClientMessage.Command(++request, NetworkAction.RESTART, sessionId = 1))
        submit(a, "look")
        assertEquals(0L, snapshots.last().completedActions)
        assertTrue(snapshots.last().players.all { it.completedActions == 0L })
    }

    @Test fun disconnectedSkipChangesFocusButDoesNotCountAsAnAction() = runBlocking<Unit> {
        val snapshots = mutableListOf<ParticipationSnapshot>()
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision = error("Missing snapshot")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot): GameMasterDecision {
                snapshots += participation
                return GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = state.players[1].id)
            }
        }
        val rooms = Rooms(gm, this)
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        val c = rooms.join(ClientMessage.Join("C", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        val state = a.connection.updates.value.adventure!!
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT, turnId = state.turnId, text = "Осмотр"))
        val acted = withTimeout(3_000) { a.connection.updates.first { it.pendingNarration == null } }
        rooms.disconnect(b.connection)
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SKIP_DISCONNECTED, turnId = acted.adventure!!.turnId))
        val skipped = c.connection.updates.value.adventure!!
        rooms.command(c.connection, ClientMessage.Command(1, NetworkAction.SUBMIT, turnId = skipped.turnId, text = "Осмотр"))
        withTimeout(3_000) { c.connection.updates.first { it.pendingNarration == null } }
        assertEquals(1L, snapshots.last().completedActions)
        assertEquals(c.connection.playerId, snapshots.last().currentPlayerId)
        assertEquals(0L, snapshots.last().players[1].completedActions)
        assertFalse(snapshots.last().players[1].online)
    }
}
