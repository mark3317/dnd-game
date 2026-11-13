package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class RoomsTest {
    @Test fun onlyHostSelectsKnownScenarioBeforeStartAndRestartPreservesIt() = runBlocking<Unit> {
        val rooms = rooms()
        val a = join(rooms, "A")
        val b = join(rooms, "B", a.welcome.room.code)
        val select = ClientMessage.Command(1, NetworkAction.SELECT_SCENARIO, scenarioId = FloodedArchive.ID)
        assertNotNull(rooms.command(b.connection, select).error)
        assertNotNull(rooms.command(a.connection, select.copy(scenarioId = "unknown")).error)
        val selected = rooms.command(a.connection, select.copy(requestId = 2))
        assertNull(selected.error)
        assertEquals(FloodedArchive.ID, selected.room.scenarioId)
        assertEquals(selected.room, b.connection.updates.value)
        val started = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START)).room.adventure!!
        assertEquals(FloodedArchive.ID, started.scenarioId)
        assertEquals(4, started.checksRemaining)
        assertTrue(started.players.all { it.location == FloodedArchive.HALL && Lighthouse.ROPE !in it.hero.inventory })
        assertNotNull(rooms.command(a.connection, select.copy(requestId = 4, scenarioId = Lighthouse.ID)).error)
        val read = rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.SUBMIT,
            turnId = started.turnId, text = "Прочитать опись")).room.adventure!!
        assertTrue(FloodedArchive.INDEX_READ in read.scene.facts)
        val restarted = rooms.command(a.connection, ClientMessage.Command(6, NetworkAction.RESTART, sessionId = started.sessionId)).room.adventure!!
        assertEquals(FloodedArchive.ID, restarted.scenarioId)
        assertTrue(restarted.scene.facts.isEmpty())
        assertEquals(4, restarted.checksRemaining)
        assertEquals(2L, restarted.sessionId)
    }

    private fun rooms() = Rooms { AdventureEngine(DiceRoller { 10 }) }
    private suspend fun join(rooms: Rooms, name: String, code: String = "") = rooms.join(ClientMessage.Join(name, code))

    @Test fun roomStartsWithActualRosterAndRejectsForeignAndDuplicateTurns() = runBlocking<Unit> {
        val rooms = rooms()
        val first = join(rooms, "Марк")
        val second = join(rooms, "Друг", first.welcome.room.code)
        assertNotNull(rooms.command(second.connection, ClientMessage.Command(1, NetworkAction.START)).error)
        // Only host may start.
        assertNull(first.connection.updates.value.adventure)
        assertNotNull(rooms.command(first.connection, ClientMessage.Command(1, NetworkAction.START)).room.adventure)
        val game = first.connection.updates.value.adventure!!
        assertEquals(listOf("Марк", "Друг"), game.players.map { it.name })
        assertNotNull(rooms.command(second.connection, ClientMessage.Command(2, NetworkAction.PASS, turnId = game.turnId)).error)
        val pass = ClientMessage.Command(2, NetworkAction.PASS, turnId = game.turnId)
        val next = rooms.command(first.connection, pass)
        assertNull(next.error)
        assertEquals(second.welcome.playerId, next.room.adventure!!.activePlayer.id)
        assertNotNull(rooms.command(first.connection, pass).error)
        assertNotNull(rooms.command(second.connection, ClientMessage.Command(3, NetworkAction.PASS, turnId = game.turnId)).error)
        assertEquals(2, first.connection.updates.value.adventure!!.turnOrder.turnNumber)
    }

    @Test fun onlyCheckOwnerCanRollAndDiceRunOnceOnServer() = runBlocking<Unit> {
        var rolls = 0
        val rooms = Rooms { AdventureEngine(DiceRoller { rolls++; 10 }) }
        val a = join(rooms, "A")
        val b = join(rooms, "B", a.welcome.room.code)
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        val game = a.connection.updates.value.adventure!!
        val requested = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT,
            turnId = game.turnId, text = Lighthouse.CLIMB.text)).room.adventure!!
        val check = requested.pendingCheck!!
        assertNotNull(rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.ROLL, checkId = check.id)).error)
        val command = ClientMessage.Command(3, NetworkAction.ROLL, checkId = check.id)
        assertNull(rooms.command(a.connection, command).error)
        assertNotNull(rooms.command(a.connection, command).error)
        assertNotNull(rooms.command(a.connection, command.copy(requestId = 4)).error)
        assertEquals(1, rolls)
        val result = a.connection.updates.value.adventure!!
        assertEquals(2, result.checksRemaining)
        assertEquals(Lighthouse.LANTERN_ROOM, result.players.first().location)
        assertEquals(b.welcome.playerId, result.activePlayer.id)
    }

    @Test fun simultaneousRollsAndTurnCommandsCannotAdvanceTwice() = runBlocking<Unit> {
        var rolls = 0
        val rooms = Rooms { AdventureEngine(DiceRoller { rolls++; 5 }) }
        val a = join(rooms, "A")
        join(rooms, "B", a.welcome.room.code)
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        val turn = a.connection.updates.value.adventure!!.turnId
        val check = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SUBMIT,
            turnId = turn, text = Lighthouse.CLIMB.text)).room.adventure!!.pendingCheck!!
        val results = (3L..12L).map { id -> async(Dispatchers.Default) {
            rooms.command(a.connection, ClientMessage.Command(id, NetworkAction.ROLL, checkId = check.id))
        } }.awaitAll()
        assertEquals(1, results.count { it.error == null })
        assertEquals(1, rolls)
        assertEquals(2, a.connection.updates.value.adventure!!.turnOrder.turnNumber)
    }

    @Test fun reconnectionRetainsIdentityAndOldConnectionCannotActOrDisconnectNewOne() = runBlocking<Unit> {
        val rooms = rooms()
        val a = join(rooms, "A")
        val b = join(rooms, "B", a.welcome.room.code)
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        rooms.disconnect(a.connection)
        assertEquals(b.welcome.playerId, b.connection.updates.value.hostPlayerId)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, a.welcome.resumeToken))
        assertEquals(a.welcome.playerId, resumed.welcome.playerId)
        rooms.disconnect(a.connection)
        assertTrue(resumed.connection.updates.value.members.first().online)
        val turn = resumed.welcome.room.adventure!!.turnId
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.PASS, turnId = turn)).error)
        assertNull(rooms.command(resumed.connection, ClientMessage.Command(1, NetworkAction.PASS, turnId = turn)).error)
        assertFailsWith<IllegalStateException> {
            rooms.join(ClientMessage.Join("A", a.welcome.room.code, "invalid"))
        }
    }

    @Test fun hostCanSkipOnlyDisconnectedActorAndCancelPendingWithoutRolling() = runBlocking<Unit> {
        var rolls = 0
        val rooms = Rooms { AdventureEngine(DiceRoller { rolls++; 20 }) }
        val a = join(rooms, "A")
        val b = join(rooms, "B", a.welcome.room.code)
        rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START))
        val turn = a.connection.updates.value.adventure!!.turnId
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SKIP_DISCONNECTED, turnId = turn)).error)
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT, turnId = turn, text = Lighthouse.CLIMB.text))
        rooms.disconnect(a.connection)
        val skipped = rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.SKIP_DISCONNECTED, turnId = turn))
        assertNull(skipped.error)
        assertNull(skipped.room.adventure!!.pendingCheck)
        assertEquals(b.welcome.playerId, skipped.room.adventure!!.activePlayer.id)
        assertEquals(3, skipped.room.adventure!!.checksRemaining)
        assertEquals(0, rolls)
    }

    @Test fun rosterAndRestartRestrictionsAreEnforced() = runBlocking<Unit> {
        val rooms = rooms()
        val a = join(rooms, "A")
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.START)).error)
        val b = join(rooms, "B", a.welcome.room.code)
        join(rooms, "C", a.welcome.room.code)
        assertFailsWith<IllegalArgumentException> { join(rooms, "D", a.welcome.room.code) }
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START))
        assertFailsWith<IllegalArgumentException> { join(rooms, "E", a.welcome.room.code) }
        val session = a.connection.updates.value.adventure!!.sessionId
        assertNotNull(rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.RESTART, sessionId = session)).error)
        assertNull(rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.RESTART, sessionId = session)).error)
        assertEquals(session + 1, a.connection.updates.value.adventure!!.sessionId)
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.RESTART, sessionId = session)).error)
    }

    @Test fun hostCanFreeAnOfflineLobbySlotWithoutDuplicatingHeroes() = runBlocking<Unit> {
        val rooms = rooms()
        val a = join(rooms, "A")
        val b = join(rooms, "B", a.welcome.room.code)
        val c = join(rooms, "C", a.welcome.room.code)
        val remove = ClientMessage.Command(1, NetworkAction.REMOVE_DISCONNECTED, targetPlayerId = b.welcome.playerId)
        assertNotNull(rooms.command(a.connection, remove).error)
        rooms.disconnect(b.connection)
        assertNotNull(rooms.command(c.connection, remove).error)
        assertNull(rooms.command(a.connection, remove.copy(requestId = 2)).error)
        val d = join(rooms, "D", a.welcome.room.code)
        assertEquals(3, d.welcome.room.members.map { it.player.hero.name }.distinct().size)
        assertFailsWith<IllegalStateException> {
            rooms.join(ClientMessage.Join("B", a.welcome.room.code, b.welcome.resumeToken))
        }
    }

    @Test fun serializedPublicSnapshotHasNoPrivateToken() = runBlocking<Unit> {
        val rooms = rooms()
        val a = join(rooms, "A")
        join(rooms, "B", a.welcome.room.code)
        val text = GameWireJson.encodeToString<ServerMessage>(ServerMessage.Snapshot(a.connection.updates.value))
        assertFalse(text.contains(a.welcome.resumeToken))
        assertFalse(text.contains("resumeToken"))
        val roundTrip = GameWireJson.decodeFromString<ServerMessage>(text) as ServerMessage.Snapshot
        assertEquals(a.connection.updates.value, roundTrip.room)
    }
}
