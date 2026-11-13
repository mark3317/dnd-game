package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

/** Real loopback TCP + production KMP client, without UI or mocked WebSocket transport. */
class NetworkIntegrationTest {
    @Test fun twoClientsSynchronizeTheirOwnTurnsAndServerDice() = runBlocking {
        val rooms = Rooms { AdventureEngine(DiceRoller { 10 }) }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val a = NetworkGameClient(scope)
        val b = NetworkGameClient(scope)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val address = "ws://127.0.0.1:$port"
            a.connect(address, "Марк")
            val first = withTimeout(15_000) { a.state.first { it.status == ConnectionStatus.CONNECTED || it.error != null } }
            assertEquals(ConnectionStatus.CONNECTED, first.status, first.error)
            b.connect(address, "Друг", first.room!!.code)
            val second = withTimeout(15_000) { b.state.first { it.status == ConnectionStatus.CONNECTED } }
            withTimeout(10_000) { a.state.first { it.room!!.members.size == 2 } }
            a.command(NetworkAction.START)
            val startA = withTimeout(10_000) { a.state.first { it.room?.adventure != null && !it.busy } }
            val startB = withTimeout(10_000) { b.state.first { it.room?.adventure != null } }
            assertEquals(startA.room, startB.room)
            val turn = startA.room!!.adventure!!.turnId
            b.command(NetworkAction.PASS, turnId = turn)
            val rejected = withTimeout(10_000) { b.state.first { !it.busy && it.error != null } }
            assertEquals(turn, rejected.room!!.adventure!!.turnId)
            a.command(NetworkAction.SUBMIT, turnId = turn, text = Lighthouse.CLIMB.text)
            val checkA = withTimeout(10_000) { a.state.first { it.room?.adventure?.pendingCheck != null && !it.busy } }
            val check = checkA.room!!.adventure!!.pendingCheck!!
            a.command(NetworkAction.ROLL, checkId = check.id)
            val rolledA = withTimeout(10_000) { a.state.first { it.room?.adventure?.turnOrder?.turnNumber == 2L && !it.busy } }
            val rolledB = withTimeout(10_000) { b.state.first { it.room?.adventure?.turnOrder?.turnNumber == 2L } }
            assertEquals(rolledA.room, rolledB.room)
            assertEquals(second.playerId, rolledB.room!!.adventure!!.activePlayer.id)
            assertEquals(10, rolledB.room!!.adventure!!.journal.mapNotNull { it.roll }.single().die)
            b.command(NetworkAction.PASS, turnId = rolledB.room!!.adventure!!.turnId)
            val third = withTimeout(10_000) { a.state.first { it.room?.adventure?.turnOrder?.turnNumber == 3L } }
            assertEquals(first.playerId, third.room!!.adventure!!.activePlayer.id)
            withTimeout(10_000) { b.state.first { !it.busy } }
            // A long journal must pass through the actual engine, not only serialize in a unit test.
            repeat(40) {
                val previousRevision = a.state.value.room!!.revision
                a.command(NetworkAction.SUBMIT, turnId = third.room!!.adventure!!.turnId, text = "я".repeat(500))
                withTimeout(10_000) { a.state.first { !it.busy && it.room!!.revision > previousRevision } }
            }
            val large = a.state.value.room!!
            assertTrue(GameWireJson.encodeToString<ServerMessage>(ServerMessage.Snapshot(large)).length > 16 * 1024)
            withTimeout(10_000) { b.state.first { it.room!!.revision == large.revision } }
            assertEquals(large, b.state.value.room)
            // Replacing a connection also exercises private resume credentials and cleanup.
            a.reconnect()
            val resumed = withTimeout(15_000) { a.state.first { it.status == ConnectionStatus.CONNECTED } }
            assertEquals(first.playerId, resumed.playerId)
            assertEquals(3, resumed.room!!.adventure!!.turnOrder.turnNumber)
        } finally {
            a.close()
            b.close()
            scope.cancel()
            server.stop(100, 1_000)
        }
    }
}
