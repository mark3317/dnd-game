package ru.markn.dnd.server

import java.util.concurrent.atomic.AtomicLong
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.ArchiveDemo
import ru.markn.dnd.network.*

/** Actual TCP, three production KMP clients, no audio device, provider or GPU. */
class RoomSpeechNetworkTest {
    @Test fun presentationAcksWorkWhileCommandBusyAndOldConnectionCannotFinishPlayback() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val clock = AtomicLong(1_000)
        val rooms = Rooms(playbackClock = clock::get, speechScope = scope,
            speechPreparer = RoomSpeechPreparer { _, _ -> error("Opening must use bundled WAV metadata") })
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val a = NetworkGameClient(scope)
        val b = NetworkGameClient(scope)
        val c = NetworkGameClient(scope)
        val clients = listOf(a, b, c)
        suspend fun await(client: NetworkGameClient, predicate: (NetworkState) -> Boolean): NetworkState =
            withTimeout(10_000) { client.state.first(predicate) }
        fun ack(client: NetworkGameClient, batch: RoomPresentation, status: RoomSpeechAckStatus,
            generation: Long = client.state.value.connectionGeneration) {
            batch.cues.forEach { cue -> client.speechAck(RoomSpeechAcknowledgement(batch.sessionId,
                batch.batchId, cue.id, status, RoomSpeechMode.AUDIO), generation) }
        }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            a.connect(address, "A")
            val first = await(a) { it.status == ConnectionStatus.CONNECTED }
            b.connect(address, "B", first.room!!.code)
            c.connect(address, "C", first.room!!.code)
            clients.forEach { await(it) { view -> view.status == ConnectionStatus.CONNECTED && view.room?.members?.size == 3 } }
            a.command(NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID)
            clients.forEach { await(it) { view -> !view.busy && view.room?.scenarioId == ArchiveDemo.ID } }
            confirmNetworkCharacters(a, b, c)
            a.command(NetworkAction.START)
            val started = await(a) { !it.busy && it.room?.presentation != null }
            val game = started.room!!.adventure!!
            clients.forEach { await(it) { view -> view.room?.presentation?.batchId == started.room!!.presentation!!.batchId } }
            val batch = started.room!!.presentation!!
            assertTrue(batch.cues.all { it.source == RoomSpeechSource.PREPARED && it.audioId != null })

            // An in-flight/rejected game command must not prevent the separate READY transport.
            a.command(NetworkAction.PASS, turnId = game.turnId)
            assertTrue(a.state.value.busy)
            clients.forEach { ack(it, batch, RoomSpeechAckStatus.READY) }
            val playing = await(a) { view -> !view.busy && view.error != null &&
                view.room?.presentation?.cues?.all { it.startsAt != null } == true }.room!!.presentation!!
            assertEquals(game, a.state.value.room!!.adventure)
            val readingEnd = maxOf(playing.cues.last().readUntil!!,
                playing.cues.first().startsAt!! + playing.cues.sumOf { it.entry.text.length * 35L } + 1_600)
            clock.set(readingEnd)
            ack(b, playing, RoomSpeechAckStatus.FINISHED)
            ack(c, playing, RoomSpeechAckStatus.FINISHED)
            for (reader in listOf(b, c)) {
                reader.command(NetworkAction.PASS, turnId = game.turnId)
                await(reader) { !it.busy && it.error != null }
            }

            // A stale callback cannot use the current socket to finish the only remaining reader.
            val oldGeneration = a.state.value.connectionGeneration
            ack(a, playing, RoomSpeechAckStatus.FINISHED, oldGeneration - 1)
            a.command(NetworkAction.PASS, turnId = game.turnId)
            val stillWaiting = await(a) { !it.busy && it.error != null }
            assertTrue(stillWaiting.room!!.presentation!!.busy)
            assertEquals(game, stillWaiting.room!!.adventure)

            // Real reconnect can either replace the socket directly or briefly disconnect.
            // Loss pauses the room; the replacement selects a fresh playback mode before FINISHED.
            // Neither path may restart or mutate the story.
            a.reconnect()
            val restored = await(a) { it.status == ConnectionStatus.CONNECTED && it.connectionGeneration > oldGeneration }
            assertEquals(first.playerId, restored.playerId)
            assertEquals(playing.batchId, restored.room!!.presentation!!.batchId)
            assertEquals(game, restored.room!!.adventure)
            assertEquals(RoomLifecycle.ACTIVE, restored.room!!.lifecycle)
            assertTrue(restored.room!!.presentation!!.busy)
            ack(a, playing, RoomSpeechAckStatus.FINISHED, oldGeneration)
            ack(a, playing, RoomSpeechAckStatus.READY)
            // A server snapshot for every fresh READY is the delivery barrier; do not race
            // FINISHED against readiness launched by separate client coroutines.
            val ready = await(a) { it.room!!.revision >= restored.room!!.revision + playing.cues.size }
            assertTrue(ready.room!!.presentation!!.busy)
            ack(a, playing, RoomSpeechAckStatus.FINISHED)
            val complete = await(a) { it.room?.presentation?.phase == RoomPresentationPhase.COMPLETE }.room!!
            clients.forEach { await(it) { view -> view.room?.revision == complete.revision } }
            assertEquals(game, complete.adventure)
            a.command(NetworkAction.PASS, turnId = game.turnId)
            val passed = await(a) { !it.busy && it.room?.adventure?.turnId != game.turnId }
            assertNull(passed.error)
            clients.forEach { await(it) { view -> view.room?.adventure?.turnId == passed.room!!.adventure!!.turnId } }
        } finally {
            clients.forEach { it.close() }
            scope.cancel()
            server.stop(100, 1_000)
        }
    }
}
