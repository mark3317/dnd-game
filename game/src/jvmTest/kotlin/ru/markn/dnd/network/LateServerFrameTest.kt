package ru.markn.dnd.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.test.*
import ru.markn.dnd.adventure.Hero
import ru.markn.dnd.adventure.Player

/** Actual Ktor reader/cancellation with buffered frames; no receive method mocked or made noncancellable. */
class LateServerFrameTest {
    @OptIn(InternalCoroutinesApi::class)
    private class ManualDispatcher : CoroutineDispatcher(), Delay {
        private val ready = ArrayDeque<Runnable>()
        private class Timer(val at: Long, val block: Runnable) { var cancelled = false }
        private val timers = mutableListOf<Timer>()
        private var now = 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) { ready.addLast(block) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val timer = Timer(now + timeMillis, Runnable { if (continuation.isActive) continuation.resume(Unit) })
            timers += timer
            continuation.invokeOnCancellation { timer.cancelled = true }
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val timer = Timer(now + timeMillis, block)
            timers += timer
            return object : DisposableHandle { override fun dispose() { timer.cancelled = true } }
        }
        fun advanceBy(millis: Long) {
            now += millis
            timers.filter { !it.cancelled && it.at <= now }.sortedBy { it.at }.forEach {
                it.cancelled = true; ready.addLast(it.block)
            }
            runReady()
        }
        fun runReady() {
            var count = 0
            while (ready.isNotEmpty()) {
                check(++count < 1_000) { "Unexpected dispatcher loop" }
                ready.removeFirst().run()
            }
        }
    }

    private class BufferedSession(dispatcher: CoroutineDispatcher) : DefaultWebSocketSession {
        override val coroutineContext: CoroutineContext = SupervisorJob() + dispatcher
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 15_000L
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override val closeReason = CompletableDeferred<CloseReason?>()
        @OptIn(InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun flush() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() { coroutineContext.cancel() }
        fun offer(message: ServerMessage) {
            check(incoming.trySend(Frame.Text(GameWireJson.encodeToString<ServerMessage>(message))).isSuccess)
        }
        private val sent = mutableListOf<ClientMessage>()
        fun messages(): List<ClientMessage> {
            while (true) {
                val frame = outgoing.tryReceive().getOrNull() ?: break
                if (frame is Frame.Text) sent += GameWireJson.decodeFromString<ClientMessage>(frame.readText())
            }
            return sent
        }
        fun dispose() { incoming.cancel(); outgoing.close(); coroutineContext.cancel() }
    }

    private class BufferedEngine(dispatcher: CoroutineDispatcher, private val sessions: List<BufferedSession>) : HttpClientEngineBase("buffered-reader-test") {
        override val config = HttpClientEngineConfig().apply { this.dispatcher = dispatcher }
        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(WebSocketCapability)
        var requests = 0
            private set
        val paths = mutableListOf<String>()
        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            paths += data.url.encodedPath
            return HttpResponseData(HttpStatusCode.SwitchingProtocols, GMTDate(), Headers.Empty,
                HttpProtocolVersion.HTTP_1_1, sessions[requests++], kotlin.coroutines.coroutineContext)
        }
    }

    private fun room(code: String = "OLD", revision: Long = 0, lifecycle: RoomLifecycle = RoomLifecycle.LOBBY): RoomView {
        val player = Player("player-$code", "Игрок", Hero("Герой", "Роль", 10, 10, emptyMap(), emptyList()), "location")
        return RoomView(code, player.id, listOf(RoomMember(player, true)), revision = revision, lifecycle = lifecycle)
    }
    private fun welcome(code: String = "OLD", lifecycle: RoomLifecycle = RoomLifecycle.LOBBY) =
        ServerMessage.Welcome("player-$code", "test-private-token-$code", room(code, lifecycle = lifecycle))

    private class MemoryStore(var ticket: ResumeTicket? = null, val failLoad: Boolean = false,
        val failSave: Boolean = false, val failClear: Boolean = false) : ResumeSessionStore {
        var clears = 0
        override fun load(): ResumeTicket? {
            if (failLoad) error("private-store-secret")
            return ticket
        }
        override fun save(ticket: ResumeTicket) {
            if (failSave) error("private-store-secret")
            this.ticket = ticket
        }
        override fun clear() {
            clears++
            if (failClear) error("private-store-secret")
            ticket = null
        }
    }

    private class Fixture(store: ResumeSessionStore = NoopResumeSessionStore) : AutoCloseable {
        val dispatcher = ManualDispatcher()
        val old = BufferedSession(dispatcher)
        val fresh = BufferedSession(dispatcher)
        val engine = BufferedEngine(dispatcher, listOf(old, fresh))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val client = NetworkGameClient(scope, store)
        init {
            val field = NetworkGameClient::class.java.getDeclaredField("http").apply { isAccessible = true }
            (field.get(client) as HttpClient).close()
            field.set(client, HttpClient(engine) {
                install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(GameWireJson) }
            })
        }
        override fun close() {
            client.close(); scope.cancel(); old.dispose(); fresh.dispose(); engine.close(); dispatcher.runReady()
        }
    }

    private fun Fixture.connectReady(lifecycle: RoomLifecycle = RoomLifecycle.LOBBY) {
        old.offer(welcome(lifecycle = lifecycle))
        client.connect("ws://unused.invalid", "Игрок")
        dispatcher.runReady()
        assertEquals(ConnectionStatus.CONNECTED, client.state.value.status, client.state.value.error)
        assertEquals("OLD", client.state.value.room!!.code)
    }

    @Test fun queuedWelcomeCancelledBeforeReaderResumesCannotReconnectAfterLeave() {
        Fixture().use { fixture ->
            fixture.client.connect("ws://unused.invalid", "Игрок")
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.CONNECTING, fixture.client.state.value.status)
            fixture.old.offer(welcome())
            fixture.client.leave()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertNull(fixture.client.state.value.room)
            assertNull(fixture.client.state.value.playerId)
        }
    }

    @Test fun queuedSnapshotCancelledBeforeReaderResumesCannotRestoreRoomAfterLeave() {
        Fixture().use { fixture ->
            fixture.connectReady()
            fixture.old.offer(ServerMessage.Snapshot(room(revision = 10)))
            fixture.client.leave()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertNull(fixture.client.state.value.room)
        }
    }

    @Test fun oldQueuedSnapshotCannotReplaceFreshWelcomeAndCurrentSnapshotsStillApply() {
        Fixture().use { fixture ->
            fixture.connectReady()
            fixture.old.offer(ServerMessage.Snapshot(room(revision = 100)))
            fixture.fresh.offer(welcome("NEW"))
            fixture.client.reconnect()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
            assertEquals("NEW", fixture.client.state.value.room!!.code)
            fixture.fresh.offer(ServerMessage.Snapshot(room("NEW", revision = 1)))
            fixture.dispatcher.runReady()
            assertEquals(1L, fixture.client.state.value.room!!.revision)
            assertEquals("player-NEW", fixture.client.state.value.playerId)
        }
    }

    @Test fun leavingFromStateObserverDoesNotApplySecondAlreadyBufferedSnapshot() {
        Fixture().use { fixture ->
            fixture.connectReady()
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val observer = observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.client.state.collect { state ->
                    if (state.room?.revision == 1L) fixture.client.leave()
                }
            }
            try {
                fixture.old.offer(ServerMessage.Snapshot(room(revision = 1)))
                fixture.old.offer(ServerMessage.Snapshot(room(revision = 2)))
                fixture.dispatcher.runReady()
                assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
                assertNull(fixture.client.state.value.room)
                assertNull(fixture.client.state.value.playerId)
            } finally { observer.cancel(); observerScope.cancel() }
        }
    }

    @Test fun normalizedPrivateTicketSurvivesClientCloseAndRestoresWithoutSavingGameState() {
        val store = MemoryStore()
        Fixture(store).use { first ->
            first.connectReady(RoomLifecycle.ACTIVE)
            assertTrue(first.client.state.value.canResumeSession)
            assertEquals("ws://unused.invalid/play", store.ticket!!.endpoint)
            assertFalse(store.ticket.toString().contains("test-private-token"))
            assertFalse(first.client.state.value.toString().contains("test-private-token"))
        }
        val saved = assertNotNull(store.ticket)
        val json = GameWireJson.encodeToString(saved)
        assertEquals(saved, GameWireJson.decodeFromString<ResumeTicket>(json))
        assertFalse(json.contains("adventure"))
        Fixture(store).use { restored ->
            assertEquals(ConnectionStatus.DISCONNECTED, restored.client.state.value.status)
            assertNull(restored.client.state.value.room)
            assertTrue(restored.client.state.value.canResumeSession)
            assertEquals(saved.endpoint, restored.client.serverAddress)
            restored.old.offer(welcome(lifecycle = RoomLifecycle.PAUSED))
            restored.client.reconnect()
            restored.dispatcher.runReady()
            assertEquals(ConnectionStatus.CONNECTED, restored.client.state.value.status, restored.client.state.value.error)
            assertEquals(listOf("/play"), restored.engine.paths)
            val join = restored.old.messages().filterIsInstance<ClientMessage.Join>().single()
            assertEquals(saved.token, join.resumeToken)
            assertEquals(saved.roomCode, join.roomCode)
            assertEquals(saved.playerName, join.name)
            assertTrue(restored.old.messages().none { it is ClientMessage.Command })
        }
    }

    @Test fun newConnectionCannotSilentlyReplaceRestoredIdentityAndOfflineLeaveIsNotAcknowledged() {
        val ticket = ResumeTicket("wss://game.example/play", "Игрок", "ROOM01", "private-token")
        val store = MemoryStore(ticket)
        Fixture(store).use { fixture ->
            fixture.client.connect("ws://different.invalid", "Другой", "OTHER1")
            fixture.dispatcher.runReady()
            assertEquals(0, fixture.engine.requests)
            assertEquals(ticket, store.ticket)
            assertTrue(fixture.client.state.value.canResumeSession)
            fixture.client.leave() // Unknown recovered party must not silently forget.
            assertEquals(ticket, store.ticket)
            fixture.client.requestLeave()
            fixture.dispatcher.runReady()
            assertFalse(fixture.client.state.value.leaveAcknowledged)
            assertFalse(fixture.client.state.value.leavePending)
            assertNotNull(fixture.client.state.value.error)
            assertEquals(ticket, store.ticket)
            assertEquals(0, fixture.engine.requests)
        }
    }

    @Test fun invalidStoredEndpointIsNotNormalizedIntoDifferentOrCredentialBearingDestination() {
        for (endpoint in listOf("ws://unused.invalid", "https://unused.invalid/play", "ws://user:private@unused.invalid/play",
            "ws://unused.invalid/play?token=private", "ws://unused.invalid/play#private")) {
            val store = MemoryStore(ResumeTicket(endpoint, "Игрок", "ROOM01", "private-token"))
            Fixture(store).use { fixture ->
                assertFalse(fixture.client.state.value.canResumeSession)
                assertNull(store.ticket)
                assertEquals("", fixture.client.serverAddress)
                assertFalse(fixture.client.state.value.error.orEmpty().contains("private"))
            }
        }
    }

    @Test fun explicitLeaveBypassesGameplayBusyButWaitsForPrivateAcknowledgement() {
        val store = MemoryStore()
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            val ticket = assertNotNull(store.ticket)
            fixture.client.command(NetworkAction.PASS)
            assertTrue(fixture.client.state.value.busy)
            fixture.client.requestLeave()
            assertTrue(fixture.client.state.value.leavePending)
            assertFalse(fixture.client.state.value.leaveAcknowledged)
            assertEquals(ticket, store.ticket)
            fixture.dispatcher.runReady()
            assertEquals(1, fixture.old.messages().count { it == ClientMessage.Leave })
            assertTrue(fixture.old.messages().none { it is ClientMessage.Command })
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
            fixture.client.requestLeave() // Do not duplicate the departure while waiting.
            fixture.dispatcher.runReady()
            assertEquals(1, fixture.old.messages().count { it == ClientMessage.Leave })
            fixture.old.offer(ServerMessage.Left)
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertTrue(fixture.client.state.value.leaveAcknowledged)
            assertFalse(fixture.client.state.value.leavePending)
            assertFalse(fixture.client.state.value.canResumeSession)
            assertNull(store.ticket)
        }
    }

    @Test fun rejectedOrTimedOutLeaveDoesNotClearRecoveryOrPretendSuccess() {
        for (timeout in listOf(false, true)) {
            val store = MemoryStore()
            Fixture(store).use { fixture ->
                fixture.connectReady(RoomLifecycle.ACTIVE)
                val ticket = store.ticket
                fixture.client.requestLeave()
                fixture.dispatcher.runReady()
                if (timeout) fixture.dispatcher.advanceBy(15_000)
                else { fixture.old.offer(ServerMessage.Error("Выход не подтверждён")); fixture.dispatcher.runReady() }
                assertFalse(fixture.client.state.value.leaveAcknowledged)
                assertFalse(fixture.client.state.value.leavePending)
                assertTrue(fixture.client.state.value.canResumeSession)
                assertEquals(ticket, store.ticket)
                assertNotNull(fixture.client.state.value.error)
                assertEquals(if (timeout) ConnectionStatus.DISCONNECTED else ConnectionStatus.CONNECTED, fixture.client.state.value.status)
                assertEquals(1, fixture.old.messages().count { it == ClientMessage.Leave })
            }
        }
    }

    @Test fun explicitLobbyDepartureWaitsForLeftInsteadOfOnlyForgettingTheLocalSeat() {
        val store = MemoryStore()
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.LOBBY)
            fixture.client.requestLeave(); fixture.dispatcher.runReady()
            assertEquals(1, fixture.old.messages().count { it == ClientMessage.Leave })
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
            assertTrue(fixture.client.state.value.leavePending)
            assertFalse(fixture.client.state.value.leaveAcknowledged)
            assertNotNull(store.ticket)
            fixture.old.offer(ServerMessage.Left); fixture.dispatcher.runReady()
            assertTrue(fixture.client.state.value.leaveAcknowledged)
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertNull(fixture.client.state.value.room)
            assertNull(store.ticket)
        }
    }

    @Test fun transportFailureDuringLeaveKeepsTicketAndDoesNotReplayDepartureOrCommands() {
        val store = MemoryStore()
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            val ticket = store.ticket
            fixture.client.requestLeave()
            fixture.dispatcher.runReady()
            fixture.old.incoming.close()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertFalse(fixture.client.state.value.leaveAcknowledged)
            assertEquals(ticket, store.ticket)
            fixture.fresh.offer(welcome(lifecycle = RoomLifecycle.PAUSED))
            fixture.client.reconnect()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
            assertTrue(fixture.fresh.messages().none { it is ClientMessage.Command || it == ClientMessage.Leave })
        }
    }

    @Test fun closedRoomRetainsPublicExplanationAndCompletingRoomDetachesWithoutAbort() {
        for (lifecycle in listOf(RoomLifecycle.CLOSED, RoomLifecycle.COMPLETED)) {
            val store = MemoryStore()
            Fixture(store).use { fixture ->
                fixture.connectReady(RoomLifecycle.ACTIVE)
                fixture.old.offer(ServerMessage.Snapshot(room(revision = 1, lifecycle = lifecycle).copy(closedByPlayerId = "other-player")))
                fixture.dispatcher.runReady()
                assertNull(store.ticket)
                assertFalse(fixture.client.state.value.canResumeSession)
                assertFalse(fixture.client.state.value.leaveAcknowledged)
                assertEquals(lifecycle, fixture.client.state.value.room!!.lifecycle)
                if (lifecycle == RoomLifecycle.CLOSED) {
                    assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
                    assertEquals("other-player", fixture.client.state.value.room!!.closedByPlayerId)
                } else {
                    assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
                    fixture.client.leave() // Automatic completion is local, not Leave for everyone.
                    fixture.dispatcher.runReady()
                    assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
                }
                assertTrue(fixture.old.messages().none { it == ClientMessage.Leave })
            }
        }
    }

    @Test fun closedSnapshotAcknowledgesPendingLeaveAndActiveLocalLeaveNeverForgets() {
        val store = MemoryStore()
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            val ticket = store.ticket
            fixture.client.leave()
            fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
            assertEquals(ticket, store.ticket)
            fixture.client.requestLeave()
            fixture.dispatcher.runReady()
            fixture.old.offer(ServerMessage.Snapshot(room(revision = 1, lifecycle = RoomLifecycle.CLOSED)))
            fixture.dispatcher.runReady()
            assertTrue(fixture.client.state.value.leaveAcknowledged)
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertNull(store.ticket)
        }
    }

    @Test fun typedResumeRejectionsClearIdentityButVersionMismatchPreservesIt() {
        for (reason in ConnectionRejection.entries) {
            val ticket = ResumeTicket("ws://unused.invalid/play", "Игрок", "ROOM01", "private-token")
            val store = MemoryStore(ticket)
            Fixture(store).use { fixture ->
                fixture.old.offer(ServerMessage.Error("Подключение отклонено", reason))
                fixture.client.reconnect()
                fixture.dispatcher.runReady()
                val keep = reason == ConnectionRejection.VERSION_MISMATCH
                assertEquals(keep, fixture.client.state.value.canResumeSession)
                assertEquals(if (keep) ticket else null, store.ticket)
                assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
                assertFalse(fixture.client.state.value.leaveAcknowledged)
            }
        }
    }

    @Test fun failedResumeOfPreviouslyConnectedRoomDoesNotLeaveAnUnrecoverableActiveView() {
        val store = MemoryStore()
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            fixture.old.incoming.close(); fixture.dispatcher.runReady()
            assertEquals(RoomLifecycle.ACTIVE, fixture.client.state.value.room!!.lifecycle)
            fixture.fresh.offer(ServerMessage.Error("Комната недоступна", ConnectionRejection.ROOM_UNAVAILABLE))
            fixture.client.reconnect(); fixture.dispatcher.runReady()
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertNull(fixture.client.state.value.room)
            assertNull(fixture.client.state.value.playerId)
            assertFalse(fixture.client.state.value.canResumeSession)
            assertNull(store.ticket)
            fixture.client.leave() // Terminal failure may be dismissed locally, without an active-party guard.
            assertNull(fixture.client.state.value.error)
        }
    }

    @Test fun completingCommandReplyCannotHideFailedDeletionOfPrivateRecoveryData() {
        val store = MemoryStore(failClear = true)
        Fixture(store).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            fixture.client.command(NetworkAction.PASS); fixture.dispatcher.runReady()
            val command = fixture.old.messages().filterIsInstance<ClientMessage.Command>().single()
            fixture.old.offer(ServerMessage.Reply(command.requestId, room = room(revision = 1, lifecycle = RoomLifecycle.COMPLETED)))
            fixture.dispatcher.runReady()
            assertEquals(RoomLifecycle.COMPLETED, fixture.client.state.value.room!!.lifecycle)
            assertFalse(fixture.client.state.value.busy)
            assertFalse(fixture.client.state.value.canResumeSession)
            assertTrue(fixture.client.state.value.error.orEmpty().contains("Не удалось удалить"))
            assertFalse(fixture.client.state.value.error.orEmpty().contains("private-store-secret"))
        }
    }

    @Test fun storeFailuresAreVisibleWithoutLeakingOrKillingTheConnectedParty() {
        val loadFailure = MemoryStore(failLoad = true)
        Fixture(loadFailure).use { fixture ->
            assertFalse(fixture.client.state.value.canResumeSession)
            assertNotNull(fixture.client.state.value.error)
            assertFalse(fixture.client.state.value.error!!.contains("private-store-secret"))
            fixture.connectReady(RoomLifecycle.ACTIVE)
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
        }
        val saveFailure = MemoryStore(failSave = true)
        Fixture(saveFailure).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            assertTrue(fixture.client.state.value.canResumeSession) // RAM identity survives failed persistence.
            assertNull(saveFailure.ticket)
            assertNotNull(fixture.client.state.value.error)
            assertFalse(fixture.client.state.value.error!!.contains("private-store-secret"))
            fixture.fresh.offer(welcome(lifecycle = RoomLifecycle.PAUSED))
            fixture.client.reconnect()
            fixture.dispatcher.runReady()
            assertEquals("test-private-token-OLD", fixture.fresh.messages().filterIsInstance<ClientMessage.Join>().single().resumeToken)
            assertEquals(ConnectionStatus.CONNECTED, fixture.client.state.value.status)
        }
        val clearFailure = MemoryStore(failClear = true)
        Fixture(clearFailure).use { fixture ->
            fixture.connectReady(RoomLifecycle.ACTIVE)
            fixture.client.requestLeave(); fixture.dispatcher.runReady()
            fixture.old.offer(ServerMessage.Left); fixture.dispatcher.runReady()
            assertTrue(fixture.client.state.value.leaveAcknowledged)
            assertFalse(fixture.client.state.value.canResumeSession)
            assertNotNull(fixture.client.state.value.error)
            assertFalse(fixture.client.state.value.error!!.contains("private-store-secret"))
        }
    }
}
