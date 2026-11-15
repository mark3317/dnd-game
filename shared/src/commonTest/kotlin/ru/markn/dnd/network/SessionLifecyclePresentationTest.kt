package ru.markn.dnd.network

import kotlin.test.*
import ru.markn.dnd.adventure.*

class SessionLifecyclePresentationTest {
    private val hero = Hero("Герой", "Воин", 10, 10, emptyMap(), emptyList())
    private val room = RoomView("ABC123", "one", listOf(
        RoomMember(Player("one", "Анна", hero, "hall"), true),
        RoomMember(Player("two", "Борис", hero, "hall"), false)), lifecycle = RoomLifecycle.ACTIVE)

    @Test fun connectedPeersPauseWithNamedMissingPlayersAndNoGameplayCommands() {
        val state = NetworkState(ConnectionStatus.CONNECTED, room.copy(lifecycle = RoomLifecycle.PAUSED,
            waitingPlayerIds = listOf("two", "unknown")))
        val ui = state.sessionPresentation()
        assertTrue(ui.paused)
        assertFalse(ui.commandsAvailable)
        assertEquals(listOf("Борис"), ui.waitingNames)
        assertFalse(state.copy(room = room).sessionPresentation().paused)
        assertTrue(state.copy(room = room).sessionPresentation().commandsAvailable)
    }

    @Test fun localDisconnectFreezesBeforeServerPauseAndClosedOrCompletedNeverResumeGameplay() {
        assertTrue(NetworkState(ConnectionStatus.DISCONNECTED, room).sessionPresentation().paused)
        assertFalse(NetworkState(ConnectionStatus.CONNECTED, room, leavePending = true).sessionPresentation().commandsAvailable)
        val closed = NetworkState(ConnectionStatus.DISCONNECTED, room.copy(lifecycle = RoomLifecycle.CLOSED, closedByPlayerId = "two"))
        assertFalse(closed.sessionPresentation().paused)
        assertFalse(closed.sessionPresentation().commandsAvailable)
        assertEquals("Борис", closed.sessionPresentation().closedByName)
        val complete = closed.copy(status = ConnectionStatus.CONNECTED, room = room.copy(lifecycle = RoomLifecycle.COMPLETED))
        assertFalse(complete.sessionPresentation().paused)
        assertFalse(complete.sessionPresentation().commandsAvailable)
    }
    @Test fun confirmedLobbyLeaveWaitsForServerWithoutClaimingItEndsEveryonesParty() {
        val lobby = NetworkState(ConnectionStatus.CONNECTED, room.copy(lifecycle = RoomLifecycle.LOBBY), canResumeSession = true)
        assertTrue(lobby.leavePresentation().requiresAcknowledgement)
        assertTrue(lobby.leavePresentation().explanation.contains("освободите своё место"))
        assertFalse(lobby.leavePresentation().explanation.contains("завершит партию"))
        val active = lobby.copy(room = room)
        assertTrue(active.leavePresentation().requiresAcknowledgement)
        assertTrue(active.leavePresentation().explanation.contains("для всех игроков"))
        val restored = NetworkState(canResumeSession = true)
        assertTrue(restored.leavePresentation().requiresAcknowledgement)
        assertTrue(restored.leavePresentation().explanation.contains("Если партия уже началась"))
        assertFalse(NetworkState().leavePresentation().requiresAcknowledgement)
    }

}
