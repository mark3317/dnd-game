package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.network.*
import ru.markn.dnd.adventure.GameMasterDecision

class SoloTestModeTest {
    @Test fun soloRequiresExplicitFlagAndAiThenUsesRealPlayerForGm() = runBlocking<Unit> {
        var calls = 0
        val rooms = Rooms(gameMaster = GameMaster { state, _ ->
            calls++
            assertEquals(1, state.players.size)
            GameMasterDecision(null, "Привет, путешественник!")
        }, aiScope = this, presentationEnabled = false)
        val a = rooms.joinReady(ClientMessage.Join("Solo"))
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).error)
        val started = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START, soloTestMode = true))
        assertNull(started.error)
        val state = assertNotNull(started.room.adventure)
        assertEquals(listOf(a.welcome.playerId), state.players.map { it.id })
        assertEquals(RoomLifecycle.ACTIVE, started.room.lifecycle)
        val reply = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT,
            turnId = state.turnId, text = "Привет, ведущий!"))
        assertNull(reply.error)
        withTimeout(3000) { a.connection.updates.first { it.pendingNarration == null } }
        assertEquals(1, calls)
        rooms.disconnect(a.connection)
        assertEquals(RoomLifecycle.PAUSED, a.connection.updates.value.lifecycle)
        val restored = rooms.join(ClientMessage.Join("Solo", a.welcome.room.code, a.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, restored.welcome.room.lifecycle)
        rooms.leave(restored.connection)
        assertEquals(RoomLifecycle.CLOSED, restored.connection.updates.value.lifecycle)
    }

    @Test fun soloCannotBypassAiOrRosterRequirements() = runBlocking<Unit> {
        val offline = Rooms(presentationEnabled = false)
        val a = offline.joinReady(ClientMessage.Join("A"))
        assertNotNull(offline.command(a.connection, ClientMessage.Command(2, NetworkAction.START, soloTestMode = true)).error)
        val rooms = Rooms(gameMaster = GameMaster { _, _ -> GameMasterDecision(null, "Ответ") }, aiScope = this, presentationEnabled = false)
        val first = rooms.joinReady(ClientMessage.Join("First"))
        val second = rooms.joinReady(ClientMessage.Join("Second", first.welcome.room.code))
        assertNotNull(rooms.command(second.connection, ClientMessage.Command(2, NetworkAction.START, soloTestMode = true)).error)
        assertNotNull(rooms.command(first.connection, ClientMessage.Command(2, NetworkAction.START, soloTestMode = true)).error)
        assertNull(rooms.command(first.connection, ClientMessage.Command(3, NetworkAction.START)).error)
    }
}
