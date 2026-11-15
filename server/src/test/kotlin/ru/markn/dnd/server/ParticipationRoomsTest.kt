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
        val rooms = Rooms(gm, this, presentationEnabled = false)
        val a = rooms.joinReady(ClientMessage.Join("A"))
        rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START))
        val state = a.connection.updates.value.adventure!!
        val pending = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT,
            turnId = state.turnId, text = "delayed")).room.pendingNarration!!
        entered.await()
        rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.CANCEL_NARRATION,
            turnId = pending.turnId, narrationId = pending.id))
        rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.SUBMIT, turnId = state.turnId, text = "look"))
        val acted = withTimeout(3_000) { a.connection.updates.first { it.pendingNarration == null } }
        assertEquals(0L, snapshots.last().completedActions)
        release.complete(Unit); exited.await(); yield()
        assertEquals(acted.adventure, a.connection.updates.value.adventure)
        rooms.command(a.connection, ClientMessage.Command(6, NetworkAction.SUBMIT,
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
        val rooms = Rooms(gm, this, presentationEnabled = false) { AdventureEngine(DiceRoller { 20 }) }
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val c = rooms.joinReady(ClientMessage.Join("C", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START))
        var request = 2L
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
        val paused = c.connection.updates.value
        val callsBeforePause = snapshots.size
        assertEquals(RoomLifecycle.PAUSED, paused.lifecycle)
        assertFalse(paused.members.single { it.player.id == b.connection.playerId }.online)
        val rejected = rooms.command(c.connection, ClientMessage.Command(++request, NetworkAction.SUBMIT,
            turnId = paused.adventure!!.turnId, text = "joke"))
        assertNotNull(rejected.error)
        assertEquals(paused, rejected.room)
        assertEquals(callsBeforePause, snapshots.size)
        val frozen = a.connection.room.participation.snapshot(paused.adventure!!,
            paused.members.filter { it.online }.map { it.player.id }.toSet())
        assertEquals(3L, frozen.completedActions)
        assertFalse(frozen.players[1].online)
        val resumed = rooms.joinReady(ClientMessage.Join("ignored", a.welcome.room.code, b.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, resumed.welcome.room.lifecycle)
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

    @Test fun disconnectedPlayerPausesWithoutSkippingFocusOrCountingParticipation() = runBlocking<Unit> {
        val snapshots = mutableListOf<ParticipationSnapshot>()
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision = error("Missing snapshot")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot): GameMasterDecision {
                snapshots += participation
                return GameMasterDecision(Lighthouse.LOOK, "Осмотр", nextPlayerId = state.players[1].id)
            }
        }
        val rooms = Rooms(gm, this, presentationEnabled = false)
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val c = rooms.joinReady(ClientMessage.Join("C", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START))
        val state = a.connection.updates.value.adventure!!
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT, turnId = state.turnId, text = "Осмотр"))
        val acted = withTimeout(3_000) { a.connection.updates.first { it.pendingNarration == null } }
        rooms.disconnect(b.connection)
        val paused = a.connection.updates.value
        assertEquals(RoomLifecycle.PAUSED, paused.lifecycle)
        assertEquals(b.connection.playerId, paused.adventure!!.activePlayer.id)
        val calls = snapshots.size
        val skip = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SKIP_DISCONNECTED, turnId = acted.adventure!!.turnId))
        assertNotNull(skip.error)
        assertEquals(paused, skip.room)
        val blocked = rooms.command(c.connection, ClientMessage.Command(2, NetworkAction.SUBMIT,
            turnId = paused.adventure!!.turnId, text = "Осмотр"))
        assertNotNull(blocked.error)
        assertEquals(paused, blocked.room)
        assertEquals(calls, snapshots.size)
        val frozen = a.connection.room.participation.snapshot(paused.adventure!!,
            paused.members.filter { it.online }.map { it.player.id }.toSet())
        assertEquals(1L, frozen.completedActions)
        assertEquals(b.connection.playerId, frozen.currentPlayerId)
        assertEquals(0L, frozen.players[1].completedActions)
        assertFalse(frozen.players[1].online)
        val resumed = rooms.joinReady(ClientMessage.Join("ignored", a.welcome.room.code, b.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, resumed.welcome.room.lifecycle)
        assertEquals(paused.adventure, resumed.welcome.room.adventure)
        assertNull(rooms.command(resumed.connection, ClientMessage.Command(1, NetworkAction.SUBMIT,
            turnId = resumed.welcome.room.adventure!!.turnId, text = "Осмотр")).error)
        withTimeout(3_000) { resumed.connection.updates.first { it.pendingNarration == null } }
        assertEquals(1L, snapshots.last().completedActions)
        assertEquals(b.connection.playerId, snapshots.last().currentPlayerId)
        assertEquals(0L, snapshots.last().players[1].completedActions)
        assertTrue(snapshots.last().players[1].online)
    }
}
