package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.*

class ArchiveDemoNetworkTest {
    @Test fun clientsPlayCombatReconnectRejectStaleCommandAndFinishStory() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val high = AtomicBoolean(false)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            gameServer(Rooms(presentationEnabled = false) { AdventureEngine(DiceRoller { if (high.get()) it else 1 }) })
        }.start()
        val clients = listOf(NetworkGameClient(scope), NetworkGameClient(scope))
        suspend fun await(client: NetworkGameClient, predicate: (NetworkState) -> Boolean) =
            withTimeout(10_000) { client.state.first { !it.busy && predicate(it) } }
        suspend fun game(): AdventureState = clients.first().state.value.room!!.adventure!!
        fun owner(state: AdventureState) = clients.first { it.state.value.playerId == state.activePlayer.id }
        suspend fun command(client: NetworkGameClient, action: NetworkAction, state: AdventureState,
            text: String = "", combatAction: DemoAction? = null, target: Tile? = null): AdventureState {
            val revision = client.state.value.room!!.revision
            client.command(action, turnId = state.turnId, checkId = state.pendingCheck?.id,
                text = text, combatAction = combatAction, combatTarget = target)
            await(client) { (it.room?.revision ?: 0) > revision || it.error != null }
            assertNull(client.state.value.error)
            val next = client.state.value.room!!.adventure!!
            clients.forEach { await(it) { current -> current.room?.revision == client.state.value.room!!.revision } }
            assertEquals(clients[0].state.value.room, clients[1].state.value.room)
            return next
        }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            clients[0].connect(address, "Первый")
            val first = await(clients[0]) { it.status == ConnectionStatus.CONNECTED }
            clients[1].connect(address, "Второй", first.room!!.code)
            clients.forEach { await(it) { s -> s.room?.members?.size == 2 && s.status == ConnectionStatus.CONNECTED } }
            clients[0].command(NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID)
            clients.forEach { await(it) { s -> s.room?.scenarioId == ArchiveDemo.ID } }
            clients[0].command(NetworkAction.START)
            clients.forEach { await(it) { s -> s.room?.adventure != null } }
            var state = game()
            state = command(owner(state), NetworkAction.SUBMIT, state, "Прочитать опись")
            state = command(owner(state), NetworkAction.SUBMIT, state, "Всем занять оборону")
            assertNotNull(state.combat)
            val intro = state.journal.flatMap { it.combatEvents }.single { it.kind == CombatEventKind.INITIATIVE }
            clients.forEach { client -> assertEquals(client.state.value.playerId, intro.ownInitiative(client.state.value.playerId).first.id) }
            val active = owner(state)
            active.reconnect()
            val resumed = await(active) { it.status == ConnectionStatus.CONNECTED }.room!!.adventure!!
            assertEquals(state, resumed)
            val token = state.turnId
            state = command(owner(state), NetworkAction.COMBAT, state, combatAction = DemoAction.GUARD)
            active.command(NetworkAction.COMBAT, turnId = token, combatAction = DemoAction.GUARD)
            await(active) { it.error != null }
            assertEquals(state, active.state.value.room!!.adventure)
            repeat(250) {
                val battle = state.combat ?: return@repeat
                val enemies = battle.fighters.filter { it.enemy && it.hp > 0 }
                val attack = enemies.firstOrNull { battle.preview(DemoAction.ATTACK, it.tile).valid }
                high.set(true)
                state = when {
                    attack != null -> command(owner(state), NetworkAction.COMBAT, state, combatAction = DemoAction.ATTACK, target = attack.tile)
                    !battle.moved && !battle.acted -> {
                        val tile = CombatDemo.tiles.filter { battle.preview(DemoAction.MOVE, it).valid }
                            .minByOrNull { t -> enemies.minOf { t.distance(it.tile) } }
                        if (tile != null) command(owner(state), NetworkAction.COMBAT, state, combatAction = DemoAction.MOVE, target = tile)
                        else { high.set(false); command(owner(state), NetworkAction.PASS, state) }
                    }
                    else -> { high.set(false); command(owner(state), NetworkAction.PASS, state) }
                }
            }
            assertNull(state.combat)
            assertTrue(ArchiveDemo.WON in state.scene.facts)
            val cinema = state.journal.flatMap { it.combatEvents }
            assertTrue(cinema.any { it.kind == CombatEventKind.MOVE && it.actor.enemy })
            assertTrue(cinema.any { it.kind == CombatEventKind.ATTACK && it.healthAfter == 0 })
            state = command(owner(state), NetworkAction.SUBMIT, state, "Выйти во двор")
            state = command(owner(state), NetworkAction.PASS, state)
            state = command(owner(state), NetworkAction.SUBMIT, state, "Договориться о копиях")
            high.set(true)
            state = command(owner(state), NetworkAction.ROLL, state)
            assertEquals(FloodedArchive.COPIES_ENDING, state.ending)
        } finally {
            clients.forEach { it.close() }
            scope.cancel()
            server.stop(100, 1_000)
        }
    }
}
