package ru.markn.dnd.network

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequest
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.util.Attributes
import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import ru.markn.dnd.adventure.TurnId

/** Exercises the production queued sender without network timing or a new transport API. */
class QueuedGameCommandTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val ready = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { ready.addLast(block) }
        fun runReady() {
            var count = 0
            while (ready.isNotEmpty()) {
                check(++count < 100) { "Unexpected dispatcher loop" }
                ready.removeFirst().run()
            }
        }
    }

    private class RecordingSession : DefaultWebSocketSession {
        override val coroutineContext: CoroutineContext = SupervisorJob()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 15_000L
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override val closeReason = CompletableDeferred<CloseReason?>()
        private val sent = mutableListOf<Frame>()
        val frames: List<Frame> get() {
            while (true) sent += outgoing.tryReceive().getOrNull() ?: break
            return sent
        }
        @OptIn(InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun send(frame: Frame) { outgoing.send(frame) }
        override suspend fun flush() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() { coroutineContext.cancel() }
        fun dispose() { coroutineContext.cancel(); incoming.cancel(); outgoing.cancel() }
        fun commands(): List<ClientMessage.Command> = frames.map { frame ->
            GameWireJson.decodeFromString<ClientMessage>((frame as Frame.Text).readText()) as ClientMessage.Command
        }
    }

    /** sendSerialized uses the real client's converter and only needs request headers. */
    private class RecordingCall(client: HttpClient) : HttpClientCall(client) {
        init {
            request = object : HttpRequest {
                override val call: HttpClientCall = this@RecordingCall
                override val method = HttpMethod.Get
                override val url = Url("ws://unused.invalid/play")
                override val attributes = Attributes()
                override val content = object : OutgoingContent.NoContent() {}
                override val headers = Headers.Empty
            }
        }
    }

    private class Fixture {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val recovery = object : ResumeSessionStore {
            var ticket: ResumeTicket? = ResumeTicket("ws://unused.invalid/play", "Игрок", "TEST01", "test-private-resume-token")
            override fun load(): ResumeTicket? = ticket
            override fun save(ticket: ResumeTicket) { this.ticket = ticket }
            override fun clear() { ticket = null }
        }
        val client = NetworkGameClient(scope, recovery)
        private val http = get<HttpClient>("http")
        val original = RecordingSession()
        val replacement = RecordingSession()
        init {
            set("generation", 1L)
            install(original)
        }
        @Suppress("UNCHECKED_CAST")
        private fun <T> get(name: String): T = NetworkGameClient::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(client) as T
        private fun set(name: String, value: Any?) = NetworkGameClient::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(client, value)
        private fun install(recording: RecordingSession) {
            set("socket", DefaultClientWebSocketSession(RecordingCall(http), recording))
            get<MutableStateFlow<NetworkState>>("mutableState").value = NetworkState(
                status = ConnectionStatus.CONNECTED, connectionGeneration = get("generation"),
                room = RoomView("TEST01", "test-owner", emptyList(), lifecycle = RoomLifecycle.LOBBY), canResumeSession = true)
        }
        fun reconnect() {
            client.reconnect() // Production generation increment and cancellation of old timeout.
            assertEquals(ConnectionStatus.CONNECTING, client.state.value.status)
            // Do not open real network: replace only the welcome/transport boundary.
            get<Job>("job").cancel()
            install(replacement)
        }
        fun replaceSocketWithoutChangingGeneration() { install(replacement) }
        fun close() {
            client.close(); scope.cancel(); dispatcher.runReady()
            original.dispose(); replacement.dispose()
        }
    }

    @Test fun queuedCommandThenLeaveNeverReachesCapturedSocket() {
        val fixture = Fixture()
        try {
            fixture.client.command(NetworkAction.PASS, turnId = TurnId(7, 3))
            assertTrue(fixture.client.state.value.busy)
            fixture.client.leave()
            fixture.dispatcher.runReady()
            assertTrue(fixture.original.frames.isEmpty())
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.client.state.value.status)
            assertFalse(fixture.client.state.value.busy)
        } finally { fixture.close() }
    }

    @Test fun queuedCommandThenReconnectIsNeitherSentToOldSocketNorReplayedOnNewSocket() {
        val fixture = Fixture()
        try {
            fixture.client.command(NetworkAction.PASS, turnId = TurnId(7, 3))
            fixture.reconnect()
            fixture.dispatcher.runReady()
            assertTrue(fixture.original.frames.isEmpty())
            assertTrue(fixture.replacement.frames.isEmpty())
            assertFalse(fixture.client.state.value.busy)
            fixture.client.command(NetworkAction.PASS, turnId = TurnId(7, 4))
            fixture.dispatcher.runReady()
            val fresh = fixture.replacement.commands().single()
            assertEquals(2L, fresh.requestId)
            assertEquals(TurnId(7, 4), fresh.turnId)
        } finally { fixture.close() }
    }

    @Test fun changedSocketIdentityAlsoRejectsQueuedCommandWithoutGenerationChange() {
        val fixture = Fixture()
        try {
            fixture.client.command(NetworkAction.PASS, turnId = TurnId(7, 3))
            fixture.replaceSocketWithoutChangingGeneration()
            fixture.dispatcher.runReady()
            assertTrue(fixture.original.frames.isEmpty())
            assertTrue(fixture.replacement.frames.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun currentConnectionSendsOneUnchangedCommandAndDoesNotRetryIt() {
        val fixture = Fixture()
        try {
            fixture.client.command(NetworkAction.SUBMIT, turnId = TurnId(7, 3), text = "Пробую действие")
            fixture.dispatcher.runReady()
            val sent = fixture.original.commands().single()
            assertEquals(1L, sent.requestId)
            assertEquals(NetworkAction.SUBMIT, sent.action)
            assertEquals(TurnId(7, 3), sent.turnId)
            assertEquals("Пробую действие", sent.text)
            fixture.dispatcher.runReady()
            fixture.client.command(NetworkAction.PASS, turnId = TurnId(7, 3)) // Still awaiting reply.
            fixture.dispatcher.runReady()
            assertEquals(1, fixture.original.commands().size)
            fixture.reconnect()
            fixture.dispatcher.runReady()
            assertTrue(fixture.replacement.frames.isEmpty())
        } finally { fixture.close() }
    }
}
