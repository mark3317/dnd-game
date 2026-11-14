package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class GameMasterNetworkTest {
    @Test fun originalDoorIntentWithUnusedNullNarrationCreatesOneSharedCheckWithoutRephrasing() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val gm = ClaudeGameMaster(ClaudeSettings("test-key", "https://ai.starimg.ru", "claude-sonnet-5-5"),
            baseClient = HttpClient(MockEngine { request ->
                calls++
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val content = body["messages"]!!.jsonArray.first().jsonObject.getValue("content")
                val text = if (content is JsonPrimitive) content.content
                    else content.jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
                val context = Json.parseToJsonElement(text).jsonObject
                val actor = context["active_player_id"]!!.jsonPrimitive.content
                val decision = buildJsonObject {
                    put("action", "OPEN_DOOR"); put("plan", JsonNull); put("narration", JsonNull); put("nextPlayerId", actor)
                }
                val response = buildJsonObject {
                    put("id", "test-message"); put("type", "message"); put("role", "assistant"); put("model", "claude-sonnet-5-5")
                    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", decision.toString()) } }
                    put("stop_reason", "end_turn")
                    putJsonObject("usage") { put("input_tokens", 100); put("output_tokens", 20) }
                }
                respond(response.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }))
        val rooms = Rooms(gm, scope)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val a = NetworkGameClient(scope)
        val b = NetworkGameClient(scope)
        suspend fun await(client: NetworkGameClient, predicate: (NetworkState) -> Boolean) =
            withTimeout(10_000) { client.state.first { !it.busy && predicate(it) } }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            a.connect(address, "A")
            val code = await(a) { it.status == ConnectionStatus.CONNECTED }.room!!.code
            b.connect(address, "B", code)
            await(a) { it.room?.members?.size == 2 }
            a.command(NetworkAction.SELECT_SCENARIO, scenarioId = FloodedArchive.ID)
            await(a) { it.room?.scenarioId == FloodedArchive.ID }
            a.command(NetworkAction.START)
            val start = await(a) { it.room?.adventure != null }.room!!.adventure!!
            val input = "Открываю замок двери подвала своими отмычками. Осторожно подбираю положение штифтов."
            a.command(NetworkAction.SUBMIT, turnId = start.turnId, text = input)
            val proposed = await(a) { it.room?.adventure?.pendingCheck != null }.room!!
            assertEquals(proposed, await(b) { it.room?.adventure?.pendingCheck != null }.room)
            assertEquals(Ability.DEXTERITY, proposed.adventure!!.pendingCheck!!.ability)
            assertEquals(start.checksRemaining, proposed.adventure!!.checksRemaining)
            assertEquals(1, proposed.adventure!!.journal.count { it.kind == EntryKind.PLAYER && it.text == input })
            assertNull(proposed.gameMasterError)
            assertFalse(proposed.adventure!!.journal.any { it.text == "Действие передано на проверку серверу." })
            assertEquals(1, calls)
        } finally { a.close(); b.close(); scope.cancel(); server.stop(100, 1_000); gm.close() }
    }

    @Test fun threeClientsReceiveCommittedEndingThenOneSharedEpilogueAfterReconnect() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val epilogue = CompletableDeferred<String>()
        var proposals = 0
        var narratives = 0
        val narrator = object : GameMaster, OutcomeNarrator {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision {
                proposals++
                return GameMasterDecision(Lighthouse.SIGNAL, "Подать сигнал", nextPlayerId = state.activePlayer.id)
            }
            override suspend fun narrate(outcome: OutcomeNarration): String {
                narratives++
                assertTrue(outcome.epilogue)
                assertEquals(Lighthouse.SIGNAL_GIVEN, outcome.after.ending)
                return epilogue.await()
            }
        }
        val rooms = Rooms(narrator, scope) { AdventureEngine(DiceRoller { 20 }) }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val clients = List(3) { NetworkGameClient(scope) }
        suspend fun await(client: NetworkGameClient, predicate: (NetworkState) -> Boolean) =
            withTimeout(10_000) { client.state.first { !it.busy && predicate(it) } }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val a = clients[0]
            a.connect(address, "A")
            val code = await(a) { it.status == ConnectionStatus.CONNECTED }.room!!.code
            clients[1].connect(address, "B", code)
            await(a) { it.room?.members?.size == 2 }
            clients[2].connect(address, "C", code)
            clients.forEach { await(it) { s -> s.status == ConnectionStatus.CONNECTED && s.room?.members?.size == 3 } }
            a.command(NetworkAction.START)
            val state = await(a) { it.room?.adventure != null }.room!!.adventure!!
            a.command(NetworkAction.SUBMIT, turnId = state.turnId, text = "Подаю сигнал фонарём")
            val check = await(a) { it.room?.adventure?.pendingCheck != null }.room!!.adventure!!.pendingCheck!!
            a.command(NetworkAction.ROLL, checkId = check.id)
            val committed = await(a) { it.room?.pendingNarration?.kind == NarrationKind.EPILOGUE }.room!!
            assertEquals(Lighthouse.SIGNAL_GIVEN, committed.adventure!!.ending)
            assertEquals(2, committed.adventure!!.checksRemaining)
            clients.drop(1).forEach {
                assertEquals(committed, await(it) { s -> s.room?.pendingNarration?.kind == NarrationKind.EPILOGUE }.room)
            }
            a.reconnect()
            assertEquals(committed.pendingNarration,
                await(a) { it.status == ConnectionStatus.CONNECTED }.room!!.pendingNarration)
            val text = "Фонарь прорезает туман, паром отзывается гудком. Спасение стало общим финалом этой ночи."
            epilogue.complete(text)
            val done = clients.map { await(it) { s -> s.room?.adventure?.journal?.lastOrNull()?.text == text }.room!! }
            assertTrue(done.all { it == done.first() })
            assertNull(done.first().pendingNarration)
            assertEquals(committed.adventure, done.first().adventure!!.copy(journal = committed.adventure!!.journal))
            assertEquals(1, proposals)
            assertEquals(1, narratives)
        } finally { clients.forEach { it.close() }; scope.cancel(); server.stop(100, 1_000) }
    }

    @Test fun socialReplyIsSharedAndSamePlayerCanThenPerformAnAction() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val reply = "Обед подождёт — приключение уже зовёт!"
        val rooms = Rooms(gameMaster = GameMaster { state, _ ->
            calls++
            when (calls) {
                1 -> GameMasterDecision(null, reply, nextPlayerId = state.activePlayer.id)
                2 -> GameMasterDecision(Lighthouse.LOOK, "У берега возвышается тёмный маяк.", nextPlayerId = state.activePlayer.id)
                else -> GameMasterDecision(Lighthouse.TALK, "Хранитель смотрит на вашего спутника.", nextPlayerId = state.players[1].id)
            }
        }, aiScope = scope)
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
            val before = withTimeout(10_000) { a.state.first { it.room?.adventure != null && !it.busy } }
                .room!!.adventure!!
            a.command(NetworkAction.SUBMIT, turnId = before.turnId, text = "Ну мы и спасатели, сначала бы пообедать")
            val answered = withTimeout(10_000) { a.state.first {
                it.room?.adventure?.journal?.lastOrNull()?.text == reply && !it.busy
            } }.room!!
            val shared = withTimeout(10_000) { b.state.first {
                it.room?.adventure?.journal?.lastOrNull()?.text == reply
            } }.room!!
            assertEquals(answered, shared)
            assertNull(answered.pendingNarration)
            assertNull(answered.gameMasterError)
            assertEquals(before, answered.adventure!!.copy(journal = before.journal))
            a.command(NetworkAction.SUBMIT, turnId = before.turnId, text = "Осматриваю берег как строгий ревизор")
            val acted = withTimeout(10_000) { a.state.first {
                it.room?.adventure?.turnId?.number == before.turnId.number + 1 && !it.busy
            } }.room!!.adventure!!
            assertEquals(before.checksRemaining, acted.checksRemaining)
            assertEquals(before.activePlayer.id, acted.activePlayer.id)
            a.command(NetworkAction.SUBMIT, turnId = acted.turnId, text = "Спрашиваю хранителя")
            val handed = withTimeout(10_000) { a.state.first {
                it.room?.adventure?.activePlayer?.id == before.players[1].id && !it.busy
            } }.room!!
            val other = withTimeout(10_000) { b.state.first {
                it.room?.adventure?.activePlayer?.id == before.players[1].id
            } }.room!!
            assertEquals(handed, other)
            assertEquals(3, calls)
        } finally {
            a.close(); b.close(); scope.cancel(); server.stop(100, 1_000)
        }
    }

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
            val plan = SceneActionPlan("Подняться по внешней стене с верёвкой", ProposedCheck(Ability.STRENGTH, 10),
                Lighthouse.ROPE, listOf(SceneEffect.Move(Lighthouse.LANTERN_ROOM)), listOf(SceneEffect.Damage(2)))
            response.complete(GameMasterDecision(null, "Уже наверху!", plan,
                nextPlayerId = started.room!!.adventure!!.players[1].id))
            val check = withTimeout(10_000) { a.state.first { it.room?.adventure?.pendingCheck != null } }
                .room!!.adventure!!.pendingCheck!!
            assertEquals(plan, check.plan)
            assertEquals(check, withTimeout(10_000) { b.state.first { it.room?.adventure?.pendingCheck != null } }
                .room!!.adventure!!.pendingCheck)
            b.command(NetworkAction.ROLL, checkId = check.id)
            val refused = withTimeout(10_000) { b.state.first { !it.busy && it.error != null } }
            assertEquals(check, refused.room!!.adventure!!.pendingCheck)
            a.reconnect()
            val ready = withTimeout(10_000) { a.state.first { it.status == ConnectionStatus.CONNECTED } }
            assertEquals(check, ready.room!!.adventure!!.pendingCheck)
            assertEquals(started.room!!.adventure!!.players[1].id, check.nextPlayerId)
            a.command(NetworkAction.ROLL, checkId = check.id)
            val rolled = withTimeout(10_000) { a.state.first { it.room?.adventure?.turnId?.number == 2L && !it.busy } }
            val shared = withTimeout(10_000) { b.state.first { it.room?.adventure?.turnId?.number == 2L } }
            assertEquals(rolled.room, shared.room)
            assertNull(rolled.room!!.pendingNarration)
            assertEquals(10, rolled.room!!.adventure!!.journal.mapNotNull { it.roll }.single().die)
            assertEquals(2, rolled.room!!.adventure!!.checksRemaining)
            assertEquals(Lighthouse.LANTERN_ROOM, rolled.room!!.adventure!!.players.first().location)
            assertEquals(started.room!!.adventure!!.players[1].id, rolled.room!!.adventure!!.activePlayer.id)
            assertFalse(rolled.room!!.adventure!!.journal.any { it.text == "Уже наверху!" })
        } finally {
            a.close(); b.close(); scope.cancel(); server.stop(100, 1_000)
        }
    }
}
