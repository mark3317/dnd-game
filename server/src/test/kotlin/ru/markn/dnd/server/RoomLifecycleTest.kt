package ru.markn.dnd.server

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class RoomLifecycleTest {
    private suspend fun start(rooms: Rooms): Pair<Rooms.Admission, Rooms.Admission> {
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        assertNull(rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).error)
        return a to b
    }

    @Test fun unconfirmedLossPausesWithoutSkippingAndAllReturningShiftTimelineOnce() = runBlocking<Unit> {
        val clock = AtomicLong(1_000)
        val rooms = Rooms(playbackClock = clock::get)
        val (a, b) = start(rooms)
        val c = a.connection.room
        val original = c.adventure!!
        rooms.disconnect(a.connection)
        assertEquals(RoomLifecycle.PAUSED, c.updates.value.lifecycle)
        assertEquals(listOf(a.welcome.playerId), c.updates.value.waitingPlayerIds)
        assertEquals(1_000L, c.updates.value.pausedAtMillis)
        clock.set(11_000)
        for (action in listOf(NetworkAction.PASS, NetworkAction.SKIP_DISCONNECTED, NetworkAction.RESTART)) {
            assertNotNull(rooms.command(b.connection, ClientMessage.Command(2, action,
                turnId = original.turnId, sessionId = original.sessionId)).error)
        }
        assertEquals(original, c.adventure)
        rooms.disconnect(b.connection)
        val resumedA = rooms.join(ClientMessage.Join("ignored", c.code, a.welcome.resumeToken))
        assertEquals(RoomLifecycle.PAUSED, resumedA.welcome.room.lifecycle)
        clock.set(21_000)
        val resumedB = rooms.join(ClientMessage.Join("ignored", c.code, b.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, resumedB.welcome.room.lifecycle)
        assertEquals(original.playback.openingAt + 20_000, c.adventure!!.playback.openingAt)
        assertEquals(original.journal, c.adventure!!.journal)
        assertEquals(original.turnId, c.adventure!!.turnId)
        rooms.disconnect(a.connection) // Old transport cannot pause the replacement.
        assertEquals(RoomLifecycle.ACTIVE, c.updates.value.lifecycle)
        rooms.join(ClientMessage.Join("ignored", c.code, b.welcome.resumeToken))
        assertEquals(original.playback.openingAt + 20_000, c.adventure!!.playback.openingAt)
    }

    @Test fun confirmedLeaveClosesBusyOrPausedSessionButLobbyLeaveOnlyRemovesSeat() = runBlocking<Unit> {
        val rooms = Rooms(presentationEnabled = false)
        val lobbyA = rooms.joinReady(ClientMessage.Join("LobbyA"))
        val lobbyB = rooms.joinReady(ClientMessage.Join("LobbyB", lobbyA.welcome.room.code))
        rooms.leave(lobbyA.connection)
        assertEquals(RoomLifecycle.LOBBY, lobbyB.connection.updates.value.lifecycle)
        assertEquals(listOf(lobbyB.welcome.playerId), lobbyB.connection.updates.value.members.map { it.player.id })
        assertEquals(lobbyB.welcome.playerId, lobbyB.connection.updates.value.hostPlayerId)
        val (a, b) = start(rooms)
        rooms.disconnect(b.connection)
        rooms.leave(a.connection)
        val closed = b.connection.updates.value
        assertEquals(RoomLifecycle.CLOSED, closed.lifecycle)
        assertEquals(a.welcome.playerId, closed.closedByPlayerId)
        assertNull(closed.pausedAtMillis)
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.RESTART,
            sessionId = closed.adventure!!.sessionId)).error)
        val failure = assertFailsWith<RoomConnectionRejected> {
            rooms.join(ClientMessage.Join("ignored", closed.code, b.welcome.resumeToken))
        }
        assertEquals(ConnectionRejection.ROOM_UNAVAILABLE, failure.reason)
    }

    @Test fun authoritativeEndingDoesNotPauseOrAbortOtherPlayersEpilogue() = runBlocking<Unit> {
        val rooms = Rooms(presentationEnabled = false)
        val (a, b) = start(rooms)
        val room = a.connection.room
        room.adventure = room.adventure!!.copy(ending = "test-authoritative-ending")
        rooms.disconnect(b.connection)
        assertEquals(RoomLifecycle.COMPLETED, room.updates.value.lifecycle)
        rooms.leave(a.connection)
        assertEquals(RoomLifecycle.COMPLETED, room.updates.value.lifecycle)
        assertNull(room.updates.value.closedByPlayerId)
        assertEquals("test-authoritative-ending", room.updates.value.adventure!!.ending)
    }

    @Test fun aiPreviewAndResultArrivingDuringPauseApplyExactlyOnceAfterResume() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enter = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generated = CompletableDeferred<Unit>()
        val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, "Что случилось с маяком?"))
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String) = error("Use streaming callback")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
                enter.complete(Unit); release.await()
                onInputPresentation(parts)
                generated.complete(Unit)
                return GameMasterDecision(Lighthouse.TALK.id, "Лампа исправна.", inputPresentation = parts)
            }
        }
        val clock = AtomicLong(1_000)
        val rooms = Rooms(gameMaster = gm, aiScope = scope, playbackClock = clock::get)
        try {
            val (a, b) = start(rooms)
            clock.set(a.connection.updates.value.adventure!!.playback.busyUntil + 1)
            val before = a.connection.room.adventure!!
            assertNull(rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT,
                turnId = before.turnId, text = "Что случилось с маяком?")).error)
            enter.await(); rooms.disconnect(b.connection)
            val paused = a.connection.updates.value
            release.complete(Unit); generated.await()
            withTimeout(3_000) { while (a.connection.room.pendingAi?.completion == null) delay(1) }
            assertEquals(paused, a.connection.updates.value)
            assertEquals(before.journal, a.connection.room.adventure!!.journal)
            assertNull(a.connection.room.adventure!!.playback.previewEntryId)
            clock.addAndGet(50_000)
            val resumed = rooms.join(ClientMessage.Join("ignored", paused.code, b.welcome.resumeToken))
            val after = resumed.welcome.room
            assertNull(after.pendingNarration)
            assertEquals(before.turnId.number + 1, after.adventure!!.turnId.number)
            assertEquals(1, after.adventure!!.journal.count { it.text == "Лампа исправна." })
            rooms.join(ClientMessage.Join("ignored", paused.code, b.welcome.resumeToken))
            assertEquals(after.adventure!!.journal, a.connection.updates.value.adventure!!.journal)
        } finally { scope.cancel() }
    }

    @Test fun preparationAndInflightAcksStayFrozenAndRecoveryAcceptsTextMode() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong(1_000)
        val release = CompletableDeferred<Unit>()
        val rooms = Rooms(playbackClock = clock::get, speechScope = scope,
            speechPreparer = RoomSpeechPreparer { _, _ -> release.await(); RoomSpeechAudio("a".repeat(64), 2_000) })
        try {
            val (a, b) = start(rooms)
            val room = a.connection.room
            rooms.disconnect(b.connection)
            val frozen = room.updates.value
            val expectedPreparations = room.speechJobs.size
            assertTrue(expectedPreparations > 0)
            release.complete(Unit)
            withTimeout(3_000) { while (room.bufferedSpeech.size < expectedPreparations) delay(1) }
            clock.set(51_000)
            val cue = frozen.presentation!!.cues.first()
            fun ack(status: RoomSpeechAckStatus) = RoomSpeechAcknowledgement(frozen.adventure!!.sessionId,
                frozen.presentation!!.batchId, cue.id, status, RoomSpeechMode.TEXT)
            rooms.speechAck(a.connection, ack(RoomSpeechAckStatus.READY))
            assertEquals(frozen, room.updates.value)
            assertTrue(room.bufferedAcks.isNotEmpty())
            val resumed = rooms.join(ClientMessage.Join("ignored", frozen.code, b.welcome.resumeToken))
            assertTrue(room.bufferedAcks.isEmpty())
            assertTrue(room.bufferedSpeech.isEmpty())
            assertEquals(frozen.presentation!!.hardDeadlineAt + 50_000, room.speech!!.view!!.hardDeadlineAt)
            rooms.speechAck(resumed.connection, ack(RoomSpeechAckStatus.READY))
            assertNotNull(room.speech!!.view!!.cues.first().startsAt) // Both READYs present, not a new 3-second wait.
            assertEquals(frozen.presentation!!.batchId, room.speech!!.view!!.batchId)
        } finally { scope.cancel() }
    }
}
