package ru.markn.dnd.server

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class RoomSpeechRoomsTest {
    @Test fun authorityFreezesPlaybackOnDisconnectAndReplaysInflightAcknowledgements() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong(1_000)
        val preparations = AtomicInteger()
        val rooms = Rooms(presentationEnabled = true, playbackClock = clock::get,
            speechPreparer = RoomSpeechPreparer { _, _ -> preparations.incrementAndGet(); RoomSpeechAudio("a".repeat(64), 20_000) },
            speechScope = scope)
        try {
            val a = rooms.joinReady(ClientMessage.Join("A"))
            val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
            rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID))
            val started = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START))
            val game = started.room.adventure!!
            val prepared = withTimeout(3_000) { a.connection.updates.first { view -> view.presentation?.cues?.all { it.audioId != null } == true } }.presentation!!
            for (cue in prepared.cues) for (player in listOf(a, b)) {
                rooms.speechAck(player.connection, RoomSpeechAcknowledgement(game.sessionId, prepared.batchId, cue.id,
                    RoomSpeechAckStatus.READY, RoomSpeechMode.AUDIO))
            }
            val playing = a.connection.updates.value.presentation!!
            val end = maxOf(playing.cues.last().readUntil!!,
                playing.cues.first().startsAt!! + playing.cues.sumOf { it.entry.text.length * 35L } + 1_600)
            clock.set(end)
            assertNotNull(rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.PASS, turnId = game.turnId)).error)
            assertEquals(game, a.connection.updates.value.adventure)
            rooms.disconnect(b.connection)
            for (cue in playing.cues) rooms.speechAck(a.connection, RoomSpeechAcknowledgement(game.sessionId, playing.batchId,
                cue.id, RoomSpeechAckStatus.FINISHED, RoomSpeechMode.AUDIO))
            assertEquals(RoomLifecycle.PAUSED, a.connection.updates.value.lifecycle)
            assertTrue(a.connection.updates.value.presentation!!.busy)
            assertEquals(game, a.connection.updates.value.adventure)
            val countBeforeReconnect = preparations.get()
            val restored = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, b.welcome.resumeToken))
            assertEquals(a.connection.updates.value.presentation, restored.welcome.room.presentation)
            assertEquals(countBeforeReconnect, preparations.get())
            assertEquals(RoomLifecycle.ACTIVE, restored.welcome.room.lifecycle)
            assertTrue(restored.welcome.room.presentation!!.busy) // Recovery instance has not selected its playback mode.
            val lastCue = restored.welcome.room.presentation!!.cues.last()
            rooms.speechAck(restored.connection, RoomSpeechAcknowledgement(game.sessionId, playing.batchId,
                lastCue.id, RoomSpeechAckStatus.READY, RoomSpeechMode.TEXT))
            rooms.speechAck(restored.connection, RoomSpeechAcknowledgement(game.sessionId, playing.batchId,
                lastCue.id, RoomSpeechAckStatus.FINISHED, RoomSpeechMode.TEXT))
            assertEquals(RoomPresentationPhase.COMPLETE, a.connection.updates.value.presentation!!.phase)
            assertNull(rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.PASS, turnId = game.turnId)).error)
            val after = a.connection.updates.value
            rooms.speechAck(a.connection, RoomSpeechAcknowledgement(game.sessionId, playing.batchId, playing.cues.first().id,
                RoomSpeechAckStatus.FINISHED, RoomSpeechMode.AUDIO))
            // The next cue may finish preparing concurrently; an old ACK must not
            // change game state, switch batches, or consume a gameplay request ID.
            val afterStaleAck = a.connection.updates.value
            assertEquals(after.adventure, afterStaleAck.adventure)
            assertEquals(after.presentation!!.batchId, afterStaleAck.presentation!!.batchId)
            assertEquals(5L, a.connection.lastRequestId)
        } finally { scope.cancel() }
    }
}
