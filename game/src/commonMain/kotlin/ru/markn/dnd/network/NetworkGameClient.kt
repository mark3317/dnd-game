package ru.markn.dnd.network

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.*

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class NetworkState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val room: RoomView? = null,
    val playerId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Call on the UI dispatcher. In-memory credentials are scoped to one endpoint. Commands are never retried automatically. */
class NetworkGameClient(private val scope: CoroutineScope) {
    private val http = HttpClient {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(GameWireJson)
        }
    }
    private val mutableState = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var timeoutJob: Job? = null
    private var socket: DefaultClientWebSocketSession? = null
    private var generation = 0L
    private var requestId = 0L
    private var pendingId: Long? = null
    private var endpoint = ""
    private var name = ""
    private var roomCode = ""
    private var token: String? = null

    fun connect(address: String, playerName: String, code: String = "") {
        val url = try { serverWebSocketUrl(address) } catch (_: Exception) {
            mutableState.value = state.value.copy(error = "Укажите адрес сервера: ws://адрес:8080 или wss://адрес.")
            return
        }
        endpoint = url
        name = playerName.trim()
        roomCode = code.trim().uppercase()
        token = null
        mutableState.value = NetworkState()
        open()
    }

    fun reconnect() {
        if (endpoint.isNotEmpty() && token != null) open()
    }

    private fun open() {
        val current = ++generation
        job?.cancel()
        timeoutJob?.cancel()
        pendingId = null
        socket = null
        mutableState.value = state.value.copy(status = ConnectionStatus.CONNECTING, busy = false, error = null)
        job = scope.launch {
            try {
                val session = withTimeout(15_000) { http.webSocketSession(urlString = endpoint) }
                socket = session
                try {
                    session.sendSerialized<ClientMessage>(ClientMessage.Join(name, roomCode, token))
                    when (val welcome = withTimeout(15_000) { session.receiveDeserialized<ServerMessage>() }) {
                        is ServerMessage.Welcome -> {
                            token = welcome.resumeToken
                            roomCode = welcome.room.code
                            mutableState.value = NetworkState(ConnectionStatus.CONNECTED, welcome.room, welcome.playerId)
                            log("network_connected")
                        }
                        is ServerMessage.Error -> {
                            mutableState.value = state.value.copy(error = welcome.message)
                            return@launch
                        }
                        else -> error("Expected welcome")
                    }
                    while (true) {
                        when (val message = session.receiveDeserialized<ServerMessage>()) {
                            is ServerMessage.Snapshot -> applyRoom(message.room)
                            is ServerMessage.Reply -> {
                                applyRoom(message.room)
                                if (message.requestId == pendingId) {
                                    timeoutJob?.cancel()
                                    pendingId = null
                                    mutableState.value = state.value.copy(busy = false, error = message.error)
                                }
                            }
                            is ServerMessage.Error -> mutableState.value = state.value.copy(error = message.message)
                            is ServerMessage.Welcome -> error("Unexpected welcome")
                        }
                    }
                } finally { session.close() }
            } catch (_: TimeoutCancellationException) {
                if (current == generation) mutableState.value = state.value.copy(error = "Сервер не отвечает. Проверьте адрес и подключитесь снова.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == generation) {
                    mutableState.value = state.value.copy(error = "Связь с сервером потеряна. Проверьте адрес и подключитесь снова.")
                    log("network_connection_failed", e::class.simpleName.orEmpty())
                }
            } finally {
                if (current == generation) {
                    socket = null
                    pendingId = null
                    timeoutJob?.cancel()
                    mutableState.value = state.value.copy(status = ConnectionStatus.DISCONNECTED, busy = false)
                }
            }
        }
    }

    private fun applyRoom(room: RoomView) {
        if (room.revision >= (state.value.room?.revision ?: -1)) mutableState.value = state.value.copy(room = room)
    }

    fun command(action: NetworkAction, turnId: TurnId? = null, checkId: CheckId? = null, text: String = "", sessionId: Long? = null, targetPlayerId: String? = null, narrationId: String? = null, scenarioId: String? = null) {
        val session = socket ?: return
        if (state.value.status != ConnectionStatus.CONNECTED || state.value.busy) return
        val id = ++requestId
        val current = generation
        pendingId = id
        mutableState.value = state.value.copy(busy = true, error = null)
        timeoutJob = scope.launch {
            delay(15_000)
            if (pendingId == id && current == generation) {
                mutableState.value = state.value.copy(error = "Сервер не подтвердил действие. Подключитесь снова, чтобы получить его результат.")
                job?.cancel()
            }
        }
        scope.launch {
            try {
                session.sendSerialized<ClientMessage>(ClientMessage.Command(id, action, turnId, checkId, text, sessionId, targetPlayerId, narrationId, scenarioId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current == generation) {
                    mutableState.value = state.value.copy(error = "Не удалось отправить действие. Подключитесь снова.")
                    job?.cancel()
                }
            }
        }
    }

    fun leave() {
        ++generation
        job?.cancel()
        timeoutJob?.cancel()
        socket = null
        token = null
        endpoint = ""
        mutableState.value = NetworkState()
    }

    fun close() { leave(); http.close() }

    private fun log(event: String, failureClass: String = "") {
        try { GameLogger.Platform.log(GameLogEvent(event, mapOf("failureClass" to failureClass))) } catch (_: Exception) { }
    }
}

/** No credentials or query parameters in addresses; authentication goes in the first private frame. */
fun serverWebSocketUrl(address: String): String {
    val url = URLBuilder(address.trim())
    require(url.protocol.name in listOf("http", "https", "ws", "wss"))
    require(url.host.isNotBlank() && url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty())
    require(url.encodedPath in listOf("", "/"))
    url.protocol = if (url.protocol.name in listOf("https", "wss")) URLProtocol.WSS else URLProtocol.WS
    url.encodedPath = "/play"
    return url.buildString()
}
