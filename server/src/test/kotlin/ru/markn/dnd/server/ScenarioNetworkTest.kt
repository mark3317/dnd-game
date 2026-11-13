package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class ScenarioNetworkTest {
    @Test fun twoClientsCompleteArchiveUsingSharedFactsAndFreePlansThenRestartSameScenario() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var requests = 0
        val gm = GameMaster { state, input ->
            assertEquals(FloodedArchive.ID, state.scenarioId)
            requests++
            val plan = when (input) {
                "читаю" -> SceneActionPlan("Прочитать опись", null, null, listOf(SceneEffect.Fact(FloodedArchive.INDEX_READ)), emptyList())
                "иду" -> SceneActionPlan("Выйти во двор", null, null, listOf(SceneEffect.Move(FloodedArchive.YARD)), emptyList())
                else -> SceneActionPlan("Предложить курьеру новый маршрут", ProposedCheck(Ability.CHARISMA, 12), null,
                    listOf(SceneEffect.Fact(FloodedArchive.COPIES_PROMISED)), emptyList())
            }
            GameMasterDecision(null, "Намерение", plan)
        }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            gameServer(Rooms(gameMaster = gm, aiScope = scope) { AdventureEngine(DiceRoller { 20 }) })
        }.start()
        val a = NetworkGameClient(scope)
        val b = NetworkGameClient(scope)
        suspend fun await(client: NetworkGameClient, predicate: (NetworkState) -> Boolean): NetworkState =
            withTimeout(10_000) { client.state.first { !it.busy && predicate(it) } }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            a.connect(address, "A")
            val first = await(a) { it.status == ConnectionStatus.CONNECTED }
            b.connect(address, "B", first.room!!.code)
            await(a) { it.room?.members?.size == 2 }
            await(b) { it.status == ConnectionStatus.CONNECTED }
            a.command(NetworkAction.SELECT_SCENARIO, scenarioId = FloodedArchive.ID)
            await(a) { it.room?.scenarioId == FloodedArchive.ID }
            await(b) { it.room?.scenarioId == FloodedArchive.ID }
            a.command(NetworkAction.START)
            var state = await(a) { it.room?.adventure != null }.room!!.adventure!!
            a.command(NetworkAction.SUBMIT, turnId = state.turnId, text = "читаю")
            state = await(b) { it.room?.adventure?.turnId?.number == 2L }.room!!.adventure!!
            assertTrue(FloodedArchive.INDEX_READ in state.scene.facts)
            b.command(NetworkAction.SUBMIT, turnId = state.turnId, text = "иду")
            state = await(a) { it.room?.adventure?.turnId?.number == 3L }.room!!.adventure!!
            a.command(NetworkAction.PASS, turnId = state.turnId)
            state = await(b) { it.room?.adventure?.turnId?.number == 4L }.room!!.adventure!!
            b.command(NetworkAction.SUBMIT, turnId = state.turnId, text = "договариваюсь")
            val pending = await(b) { it.room?.adventure?.pendingCheck != null }.room!!.adventure!!.pendingCheck!!
            b.reconnect()
            assertEquals(pending, await(b) { it.status == ConnectionStatus.CONNECTED }.room!!.adventure!!.pendingCheck)
            b.command(NetworkAction.ROLL, checkId = pending.id)
            val finishedA = await(a) { it.room?.adventure?.ending != null }.room!!
            val finishedB = await(b) { it.room?.adventure?.ending != null }.room!!
            assertEquals(finishedA, finishedB)
            assertEquals(FloodedArchive.COPIES_ENDING, finishedA.adventure!!.ending)
            assertEquals(3, finishedA.adventure!!.checksRemaining)
            assertEquals(1, finishedA.adventure!!.journal.count { it.roll != null })
            assertEquals(3, requests)
            a.command(NetworkAction.RESTART, sessionId = finishedA.adventure!!.sessionId)
            val restarted = await(a) { it.room?.adventure?.sessionId == 2L }.room!!.adventure!!
            assertEquals(FloodedArchive.ID, restarted.scenarioId)
            assertEquals(4, restarted.checksRemaining)
            assertTrue(restarted.scene.facts.isEmpty())
        } finally { a.close(); b.close(); scope.cancel(); server.stop(100, 1_000) }
    }
}
