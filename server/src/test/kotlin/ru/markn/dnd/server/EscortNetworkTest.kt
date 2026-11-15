package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.*

/** Real TCP and production KMP clients; no UI, TTS, paid model or injected game-state patches. */
class EscortNetworkTest {
    private class Party(scope: CoroutineScope, count: Int = 3, master: GameMaster? = null) {
        val high = AtomicBoolean(false)
        private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            gameServer(Rooms(gameMaster = master, aiScope = scope, presentationEnabled = false) {
                AdventureEngine(DiceRoller { sides -> if (high.get()) sides else 1 })
            })
        }.start()
        val clients = List(count) { NetworkGameClient(scope) }
        var address = ""
            private set
        fun game() = clients.first().state.value.room!!.adventure!!
        fun owner(state: AdventureState = game()) = clients.single { it.state.value.playerId == state.activePlayer.id }
        suspend fun synchronized(): RoomView {
            val states = withTimeout(10_000) {
                combine(clients.map { it.state }) { it.toList() }.first { states ->
                    states.all { it.status == ConnectionStatus.CONNECTED && !it.busy && it.room != null } &&
                        states.map { it.room!!.revision }.distinct().size == 1
                }
            }
            val room = states.first().room!!
            states.forEach { assertEquals(room, it.room) }
            return room
        }
        suspend fun connect() {
            address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            var code = ""
            clients.forEachIndexed { index, client ->
                client.connect(address, "Участник $index", code)
                val connected = withTimeout(10_000) { client.state.first { it.status == ConnectionStatus.CONNECTED } }
                code = connected.room!!.code
            }
            synchronized()
            clients.first().command(NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID)
            withTimeout(10_000) { clients.first().state.first { !it.busy && it.room?.scenarioId == ArchiveDemo.ID } }
            confirmNetworkCharacters(*clients.toTypedArray())
            clients.first().command(NetworkAction.START)
            withTimeout(10_000) { clients.first().state.first { !it.busy && it.room?.adventure != null } }
            synchronized()
        }
        suspend fun command(action: NetworkAction, text: String = "", combatAction: DemoAction? = null, target: Tile? = null): AdventureState {
            val before = game()
            val client = owner(before)
            val revision = client.state.value.room!!.revision
            client.command(action, turnId = before.turnId, checkId = before.pendingCheck?.id,
                text = text, combatAction = combatAction, combatTarget = target)
            val result = withTimeout(10_000) { client.state.first { !it.busy && ((it.room?.revision ?: 0) > revision || it.error != null) } }
            assertNull(result.error)
            return synchronized().adventure!!
        }
        suspend fun focus(index: Int = 0) {
            repeat(clients.size) {
                if (game().activePlayer.id == clients[index].state.value.playerId) return
                command(NetworkAction.PASS)
            }
            assertEquals(clients[index].state.value.playerId, game().activePlayer.id)
        }
        suspend fun story(id: String, index: Int = 0) {
            focus(index)
            command(NetworkAction.SUBMIT, text = game().scenario.actions.single { it.id == id }.text)
        }
        suspend fun beginEscort() {
            story("READ_INDEX")
            story("GO_YARD")
            story("ESCORT")
            val state = game()
            assertEquals("courier_escort", state.combat!!.encounterVariantId)
            assertEquals(EscortPhase.ACTIVE, state.combat!!.escortObjective!!.phase)
            assertTrue(state.players.all { it.location == FloodedArchive.YARD })
            assertEquals(clients.size + 2, state.combat!!.initiative.size)
        }
        suspend fun reject(client: NetworkGameClient, turn: TurnId, action: DemoAction, target: Tile?) {
            val before = game()
            client.command(NetworkAction.COMBAT, turnId = turn, combatAction = action, combatTarget = target)
            val denied = withTimeout(10_000) { client.state.first { !it.busy && it.error != null } }
            assertEquals(before, denied.room!!.adventure)
            assertEquals(before, synchronized().adventure)
        }
        suspend fun reconnect(client: NetworkGameClient) {
            val before = game()
            client.reconnect()
            val restored = withTimeout(10_000) { client.state.first { it.status == ConnectionStatus.CONNECTED } }
            assertEquals(before, restored.room!!.adventure)
            assertEquals(before, synchronized().adventure)
        }
        /** Free the next path cell if this hero blocks it, then approach/push without attacks. */
        suspend fun escortStep(): Boolean {
            val battle = game().combat ?: return false
            val cart = battle.escortObjective!!
            if (battle.preview(DemoAction.ESCORT, cart.tile).valid) {
                command(NetworkAction.COMBAT, combatAction = DemoAction.ESCORT, target = cart.tile)
                return true
            }
            if (!battle.moved) {
                val next = cart.definition.path.getOrNull(cart.step + 1)
                val destination = if (battle.active.tile == next) {
                    CombatDemo.tiles.filter { it !in cart.definition.path && battle.preview(DemoAction.MOVE, it).valid }
                        .minByOrNull { battle.pathTo(it).size }
                } else CombatDemo.tiles.filter { tile ->
                    tile != next && tile.distance(cart.tile) == 1 && battle.preview(DemoAction.MOVE, tile).valid
                }.minByOrNull { battle.pathTo(it).size }
                if (destination != null) {
                    command(NetworkAction.COMBAT, combatAction = DemoAction.MOVE, target = destination)
                    return false
                }
            }
            command(NetworkAction.PASS)
            return false
        }
        suspend fun defeatRemainingEnemies() {
            repeat(250) {
                val battle = game().combat ?: return
                val enemies = battle.fighters.filter { it.enemy && it.hp > 0 }
                val attack = enemies.firstOrNull { battle.preview(DemoAction.ATTACK, it.tile).valid }
                high.set(true)
                when {
                    attack != null -> command(NetworkAction.COMBAT, combatAction = DemoAction.ATTACK, target = attack.tile)
                    !battle.moved && !battle.acted -> {
                        val tile = CombatDemo.tiles.filter { battle.preview(DemoAction.MOVE, it).valid }
                            .minByOrNull { t -> enemies.minOf { t.distance(it.tile) } }
                        if (tile != null) command(NetworkAction.COMBAT, combatAction = DemoAction.MOVE, target = tile)
                        else { high.set(false); command(NetworkAction.PASS) }
                    }
                    else -> { high.set(false); command(NetworkAction.PASS) }
                }
            }
            fail("TCP fight did not finish within the command limit")
        }
        fun close() { clients.forEach { it.close() }; server.stop(100, 1_000) }
    }

    @Test fun threeClientsShareFivePushesReconnectAndRejectForeignOrRepeatedProgress() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val party = Party(scope)
        try {
            party.connect(); party.beginEscort()
            val initial = party.game()
            val battle = initial.combat!!
            assertEquals(Tile(1, 4), battle.fighters.single { it.id == initial.players[2].id }.tile)
            val foreign = party.clients.first { it !== party.owner() }
            party.reject(foreign, initial.turnId, DemoAction.ESCORT, battle.escortObjective!!.tile)
            var checkedRecovery = false
            var retiredTurn: TurnId? = null
            var checkedStaleWithValidAction = false
            repeat(80) {
                val current = party.game().combat ?: return@repeat
                val cart = current.escortObjective!!
                if (!checkedRecovery && current.preview(DemoAction.ESCORT, cart.tile).valid) {
                    val actor = party.owner()
                    party.reconnect(actor) // Before accepted progress.
                    val previous = party.game()
                    val other = party.clients.first { it !== actor }
                    party.reject(other, previous.turnId, DemoAction.ESCORT, cart.tile)
                    party.command(NetworkAction.COMBAT, combatAction = DemoAction.ESCORT, target = cart.tile)
                    retiredTurn = previous.turnId
                    val advanced = party.game()
                    assertEquals(1, advanced.combat!!.escortObjective!!.step)
                    val events = advanced.journal.flatMap { it.combatEvents }.filter { it.kind == CombatEventKind.ESCORT_MOVE }
                    assertEquals(1, events.size)
                    assertTrue(events.single().rolls.isEmpty())
                    assertEquals(previous.combat!!.active.tile, advanced.combat!!.fighters.single { it.id == previous.activePlayer.id }.tile)
                    party.reject(actor, previous.turnId, DemoAction.ESCORT, cart.tile)
                    party.reconnect(actor) // After accepted progress, no automatic resend.
                    assertEquals(1, party.game().combat!!.escortObjective!!.step)
                    checkedRecovery = true
                } else {
                    if (checkedRecovery && !checkedStaleWithValidAction && current.preview(DemoAction.ESCORT, cart.tile).valid) {
                        // The current actor/target/action are valid: only the retired token must reject this.
                        party.reject(party.owner(), requireNotNull(retiredTurn), DemoAction.ESCORT, cart.tile)
                        checkedStaleWithValidAction = true
                    }
                    party.escortStep()
                }
            }
            assertTrue(checkedRecovery)
            assertTrue(checkedStaleWithValidAction)
            val completed = party.game()
            assertEquals(ArchiveDemo.COURIER_SAFE, completed.ending)
            assertTrue(ArchiveDemo.ESCAPED in completed.scene.facts)
            assertFalse(ArchiveDemo.WON in completed.scene.facts)
            assertFalse(ArchiveDemo.WINDOW_LOST in completed.scene.facts)
            assertEquals(initial.checksRemaining, completed.checksRemaining)
            assertEquals(5, completed.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.ESCORT_MOVE })
            assertTrue(completed.players.all { it.location == FloodedArchive.YARD })
            val room = party.synchronized()
            val encoded = GameWireJson.encodeToString<ServerMessage>(ServerMessage.Snapshot(room))
            assertEquals(room, (GameWireJson.decodeFromString<ServerMessage>(encoded) as ServerMessage.Snapshot).room)
            assertFalse(encoded.contains("resumeToken"))
            party.reconnect(party.clients.first())
            assertEquals(completed, party.game())
        } finally { party.close(); scope.cancel() }
    }

    @Test fun missedWindowKeepsProgressAndResourcesThenVictoryAllowsOnlyOriginals() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var forgedRequests = 0
        val forgedCopies = GameMaster { state, _ ->
            forgedRequests++
            GameMasterDecision(null, "Попробовать прежний маршрут копий.", SceneActionPlan("Скопировать после закрытия пути",
                ProposedCheck(Ability.CHARISMA, 10, setOf(CheckDomain.PERSUASION)), null,
                listOf(SceneEffect.Fact(FloodedArchive.COPIES_PROMISED)), emptyList()), nextPlayerId = state.activePlayer.id)
        }
        val party = Party(scope, master = forgedCopies)
        try {
            party.connect(); party.beginEscort()
            var progressed = false
            repeat(50) {
                if (progressed) return@repeat
                progressed = party.escortStep()
            }
            assertTrue(progressed)
            assertEquals(1, party.game().combat!!.escortObjective!!.step)
            val initiative = party.game().combat!!.initiative
            var beforeExpiration: CombatDemo? = null
            repeat(100) {
                val current = party.game().combat ?: fail("Fight ended before the escort window")
                if (current.escortObjective!!.phase == EscortPhase.EXPIRED) return@repeat
                beforeExpiration = current
                val skillTarget = current.fighters.firstOrNull { it.enemy && it.hp > 0 && current.preview(DemoAction.SKILL, it.tile).valid }
                if (skillTarget != null) party.command(NetworkAction.COMBAT, combatAction = DemoAction.SKILL, target = skillTarget.tile)
                else party.command(NetworkAction.PASS)
            }
            val expiredState = party.game()
            val expired = expiredState.combat!!
            assertEquals(EscortPhase.EXPIRED, expired.escortObjective!!.phase)
            assertEquals(1, expired.escortObjective!!.step)
            assertTrue(expired.round >= 7)
            assertEquals(initiative, expired.initiative)
            assertEquals(beforeExpiration!!.fighters.filterNot { it.enemy }, expired.fighters.filterNot { it.enemy })
            assertEquals(beforeExpiration!!.spentSkills, expired.spentSkills)
            assertTrue(expired.spentSkills.isNotEmpty())
            assertTrue(ArchiveDemo.WINDOW_LOST in expiredState.scene.facts)
            assertEquals(1, expiredState.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.ESCORT_EXPIRED })
            party.reconnect(party.owner())
            assertEquals(expiredState, party.game())
            party.reject(party.owner(), expiredState.turnId, DemoAction.ESCORT, expired.escortObjective!!.tile)
            party.defeatRemainingEnemies()
            val victory = party.game()
            assertNull(victory.combat)
            assertNull(victory.ending)
            assertTrue(ArchiveDemo.WON in victory.scene.facts)
            assertTrue(ArchiveDemo.WINDOW_LOST in victory.scene.facts)
            assertFalse(ArchiveDemo.ESCAPED in victory.scene.facts)
            assertFalse(victory.suggestions.any { it.id == "REQUEST_COPIES" })
            party.story("GO_YARD")
            party.focus()
            val beforeForgery = party.game()
            party.command(NetworkAction.SUBMIT, text = "Уговариваю Егора доставить копии вопреки смытой дороге")
            val failedPlan = withTimeout(10_000) { party.clients.first().state.first { it.room?.pendingNarration == null && it.room?.gameMasterError != null } }
            assertEquals(1, forgedRequests)
            assertEquals(beforeForgery, failedPlan.room!!.adventure)
            assertEquals(beforeForgery, party.synchronized().adventure)
            party.story("GO_HALL")
            party.high.set(true)
            for (id in listOf("OPEN_DOOR", "GO_CELLAR", "OPEN_CHEST", "SAVE_DOCUMENTS")) {
                party.story(id)
                if (party.game().pendingCheck != null) party.command(NetworkAction.ROLL)
            }
            assertEquals(FloodedArchive.ORIGINALS_ENDING, party.game().ending)
            assertTrue(ArchiveDemo.WINDOW_LOST in party.game().scene.facts)
            party.synchronized()
        } finally { party.close(); scope.cancel() }
    }

    @Test fun ordinaryDefenseStillHasNoCartAndMismatchedWireVersionIsRejected() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val party = Party(scope)
        val raw = HttpClient(ClientCIO) { install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(GameWireJson) } }
        try {
            party.connect(); party.story("DEFEND")
            val before = party.game()
            assertNull(before.combat!!.escortObjective)
            assertNull(before.combat!!.encounterVariantId)
            assertTrue(ArchiveDemo.READY in before.scene.facts)
            assertFalse(ArchiveDemo.ESCORT_READY in before.scene.facts)
            raw.webSocket(urlString = party.address + "/play") {
                sendSerialized<ClientMessage>(ClientMessage.Join("Старая версия", version = PROTOCOL_VERSION - 1))
                val reply = withTimeout(5_000) { receiveDeserialized<ServerMessage>() }
                assertTrue(reply is ServerMessage.Error)
                assertTrue((reply as ServerMessage.Error).message.contains("Версия"))
            }
            assertEquals(before, party.synchronized().adventure)
        } finally { raw.close(); party.close(); scope.cancel() }
    }
}
