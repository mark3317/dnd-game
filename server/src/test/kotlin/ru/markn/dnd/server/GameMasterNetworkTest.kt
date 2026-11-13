package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class GameMasterNetworkTest {
    @Test fun realClientsSeePendingReplyAndOneServerOwnedCheck() = runBlocking<Unit> {
        val response = CompletableDeferred<GameMasterDecision>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val rooms = Rooms(gameMaster = GameMaster { _, _ -> response.await() }, aiScope = scope) {
            AdventureEngine(DiceRoller { 10 })
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val a = NetworkGameClient(scope)
        val b = NetworkGameClient(scope)
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            a.connect(address, "A")
            val connected = withTimeout(10_000) { a.state.first { it.status == ConnectionStatus.CONNECTED } }
            b.connect(address, "B", connected.room!!.code)
            withTimeout(10_000) { a.state.first { it.room?.members?.size == 2 } }
            a.command(NetworkAction.START)
            val started = withTimeout(10_000) { a.state.first { it.room?.adventure != null && !it.busy } }
            a.command(NetworkAction.SUBMIT, turnId = started.room!!.adventure!!.turnId, text = "Осторожно поднимаюсь по лестнице")
            val pending = withTimeout(10_000) { a.state.first { it.room?.pendingNarration != null && !it.busy } }
            assertTrue(pending.room!!.gameMasterEnabled)
            val other = withTimeout(10_000) { b.state.first { it.room?.pendingNarration != null } }
            assertEquals(pending.room!!.pendingNarration, other.room!!.pendingNarration)
            // Reconnection receives the operation's status, without repeating the submitted action.
            a.reconnect()
            val resumed = withTimeout(10_000) { a.state.first { it.status == ConnectionStatus.CONNECTED } }
            assertEquals(pending.room!!.pendingNarration, resumed.room!!.pendingNarration)
            response.complete(GameMasterDecision(DemoAction.CLIMB, "Подняться по мокрой лестнице."))
            val check = withTimeout(10_000) { a.state.first { it.room?.adventure?.pendingCheck != null } }
                .room!!.adventure!!.pendingCheck!!
            a.command(NetworkAction.ROLL, checkId = check.id)
            val rolled = withTimeout(10_000) { a.state.first { it.room?.adventure?.turnId?.number == 2L && !it.busy } }
            val shared = withTimeout(10_000) { b.state.first { it.room?.adventure?.turnId?.number == 2L } }
            assertEquals(rolled.room, shared.room)
            assertNull(rolled.room!!.pendingNarration)
            assertEquals(10, rolled.room!!.adventure!!.journal.mapNotNull { it.roll }.single().die)
            assertEquals(2, rolled.room!!.adventure!!.checksRemaining)
        } finally {
            a.close(); b.close(); scope.cancel(); server.stop(100, 1_000)
        }
    }
}
