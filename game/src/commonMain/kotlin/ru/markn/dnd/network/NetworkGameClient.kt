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
    val serverTimeOffsetMillis: Long = 0,
    val connectionGeneration: Long = 0,
    val canResumeSession: Boolean = false,
    val leavePending: Boolean = false,
    val leaveAcknowledged: Boolean = false,
)

/** Call on the UI dispatcher. Private recovery identity is scoped to one endpoint; commands are never replayed. */
class NetworkGameClient(private val scope: CoroutineScope, private val resumeStore: ResumeSessionStore = NoopResumeSessionStore) {
    private val http = HttpClient {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(GameWireJson)
        }
    }
    private val mutableState = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var timeoutJob: Job? = null
    private var leaveTimeoutJob: Job? = null
    private var socket: DefaultClientWebSocketSession? = null
    private var generation = 0L
    private var requestId = 0L
    private var pendingId: Long? = null
    private var endpoint = ""
    private var name = ""
    private var roomCode = ""
    private var token: String? = null
    private var resumeTicket: ResumeTicket? = null

    /** Normalized public endpoint for media transport; never includes the private recovery token. */
    val serverAddress: String get() = resumeTicket?.endpoint ?: endpoint

    init {
        var issue: String? = null
        val loaded = try { resumeStore.load() } catch (failure: Exception) {
            log("network_resume_load_failed", failure::class.simpleName.orEmpty())
            issue = "Не удалось загрузить данные восстановления на устройстве."
            null
        }
        if (loaded != null && !loaded.usable()) {
            issue = clearTicket() ?: "Данные восстановления на устройстве повреждены. Подключитесь заново."
        } else resumeTicket = loaded
        mutableState.value = NetworkState(canResumeSession = resumeTicket != null, error = issue)
    }

    fun connect(address: String, playerName: String, code: String = "") {
        if (resumeTicket != null) {
            mutableState.value = state.value.copy(error = "Сначала восстановите прежнюю комнату или подтвердите выход из неё. Прежнее подключение не заменено.")
            return
        }
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
        if (state.value.leavePending) return
        val ticket = resumeTicket ?: return
        endpoint = ticket.endpoint
        name = ticket.playerName
        roomCode = ticket.roomCode
        token = ticket.token
        open()
    }

    private fun open() {
        val current = ++generation
        job?.cancel()
        timeoutJob?.cancel()
        leaveTimeoutJob?.cancel()
        pendingId = null
        socket = null
        mutableState.value = state.value.copy(status = ConnectionStatus.CONNECTING, busy = false, error = null,
            canResumeSession = resumeTicket != null, leavePending = false, leaveAcknowledged = false)
        job = scope.launch {
            try {
                val session = withTimeout(15_000) { http.webSocketSession(urlString = endpoint) }
                socket = session
                try {
                    val joinSentAt = storyNowMillis()
                    session.sendSerialized<ClientMessage>(ClientMessage.Join(name, roomCode, token))
                    when (val welcome = withTimeout(15_000) { session.receiveDeserialized<ServerMessage>() }) {
                        is ServerMessage.Welcome -> {
                            token = welcome.resumeToken
                            roomCode = welcome.room.code
                            val issue = if (welcome.room.lifecycle in setOf(RoomLifecycle.CLOSED, RoomLifecycle.COMPLETED)) clearTicket()
                                else saveTicket(ResumeTicket(endpoint, name, roomCode, welcome.resumeToken))
                            val connected = NetworkState(ConnectionStatus.CONNECTED, welcome.room, welcome.playerId,
                                error = issue, canResumeSession = resumeTicket != null,
                                serverTimeOffsetMillis = welcome.room.serverTimeMillis - (joinSentAt + storyNowMillis()) / 2,
                                connectionGeneration = current)
                            if (welcome.room.lifecycle == RoomLifecycle.CLOSED) {
                                detach(connected.copy(status = ConnectionStatus.DISCONNECTED))
                                return@launch
                            }
                            mutableState.value = connected
                            log("network_connected")
                        }
                        is ServerMessage.Error -> {
                            applyError(welcome)
                            return@launch
                        }
                        else -> error("Expected welcome")
                    }
                    while (true) {
                        val message = session.receiveDeserialized<ServerMessage>()
                        if (current != generation || socket !== session) return@launch
                        when (message) {
                            is ServerMessage.Snapshot -> if (applyRoom(message.room)) return@launch
                            is ServerMessage.Reply -> {
                                if (applyRoom(message.room)) return@launch
                                if (message.requestId == pendingId) {
                                    timeoutJob?.cancel()
                                    pendingId = null
                                    mutableState.value = state.value.copy(busy = false, error = message.error ?: state.value.error)
                                }
                            }
                            is ServerMessage.Error -> if (applyError(message)) return@launch
                            ServerMessage.Left -> {
                                acknowledgeLeave()
                                return@launch
                            }
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
                    leaveTimeoutJob?.cancel()
                    mutableState.value = state.value.copy(status = ConnectionStatus.DISCONNECTED, busy = false,
                        canResumeSession = resumeTicket != null, leavePending = false)
                }
            }
        }
    }

    /** Returns true when the authoritative room closed and the reader should stop. */
    private fun applyRoom(room: RoomView): Boolean {
        if (room.revision < (state.value.room?.revision ?: -1)) return false
        val issue = if (room.lifecycle in setOf(RoomLifecycle.CLOSED, RoomLifecycle.COMPLETED)) clearTicket() else null
        if (room.lifecycle == RoomLifecycle.CLOSED) {
            detach(state.value.copy(status = ConnectionStatus.DISCONNECTED, room = room, busy = false,
                error = issue, canResumeSession = false, leavePending = false,
                leaveAcknowledged = state.value.leavePending))
            return true
        }
        mutableState.value = state.value.copy(room = room, canResumeSession = resumeTicket != null,
            error = issue ?: state.value.error)
        return false
    }

    private fun saveTicket(ticket: ResumeTicket): String? {
        resumeTicket = ticket
        return try { resumeStore.save(ticket); null } catch (failure: Exception) {
            log("network_resume_save_failed", failure::class.simpleName.orEmpty())
            "Не удалось сохранить восстановление на устройстве. Пока приложение открыто, подключение можно восстановить."
        }
    }

    private fun clearTicket(): String? {
        resumeTicket = null
        token = null
        return try { resumeStore.clear(); null } catch (failure: Exception) {
            log("network_resume_clear_failed", failure::class.simpleName.orEmpty())
            "Не удалось удалить данные восстановления на устройстве. Для этой комнаты восстановление больше недоступно."
        }
    }

    /** Typed rejection invalidates credentials, never by guessing from provider/error prose. */
    private fun applyError(message: ServerMessage.Error): Boolean {
        leaveTimeoutJob?.cancel()
        val invalidResume = message.reason in setOf(ConnectionRejection.ROOM_UNAVAILABLE, ConnectionRejection.RESUME_REJECTED)
        val issue = if (invalidResume) clearTicket() else null
        val failed = state.value.copy(error = listOfNotNull(message.message, issue).joinToString(" "),
            room = if (invalidResume) null else state.value.room,
            playerId = if (invalidResume) null else state.value.playerId,
            canResumeSession = resumeTicket != null, leavePending = false, leaveAcknowledged = false)
        if (message.reason != null) {
            detach(failed.copy(status = ConnectionStatus.DISCONNECTED, busy = false))
            return true
        }
        mutableState.value = failed
        return false
    }

    private fun acknowledgeLeave() {
        val issue = clearTicket()
        detach(NetworkState(error = issue, leaveAcknowledged = true))
    }

    /** Ends this transport only; the server distinguishes it from ClientMessage.Leave. */
    private fun detach(next: NetworkState) {
        ++generation
        job?.cancel()
        timeoutJob?.cancel()
        leaveTimeoutJob?.cancel()
        pendingId = null
        socket = null
        mutableState.value = next
    }

    /** Never changes gameplay busy/requestId and never resends an ACK after reconnection. */
    fun speechAck(acknowledgement: RoomSpeechAcknowledgement, expectedConnectionGeneration: Long) {
        val session = socket ?: return
        val current = generation
        val presentation = state.value.room?.presentation ?: return
        if (state.value.status != ConnectionStatus.CONNECTED || current != expectedConnectionGeneration ||
            acknowledgement.sessionId != presentation.sessionId || acknowledgement.batchId != presentation.batchId ||
            presentation.cues.none { it.id == acknowledgement.cueId }) return
        scope.launch {
            if (current != generation || socket !== session) return@launch
            try {
                session.sendSerialized<ClientMessage>(ClientMessage.SpeechAck(acknowledgement))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log("network_speech_ack_failed", failure::class.simpleName.orEmpty()) }
        }
    }

    fun command(action: NetworkAction, turnId: TurnId? = null, checkId: CheckId? = null, text: String = "", sessionId: Long? = null, targetPlayerId: String? = null, narrationId: String? = null, scenarioId: String? = null, character: CharacterProfile? = null, useRacialTalent: Boolean = false, combatAction: ru.markn.dnd.combat.DemoAction? = null, combatTarget: ru.markn.dnd.combat.Tile? = null, soloTestMode: Boolean = false) {
        val session = socket ?: return
        if (state.value.status != ConnectionStatus.CONNECTED || state.value.busy || state.value.leavePending) return
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
            if (current != generation || socket !== session || state.value.leavePending) return@launch
            try {
                session.sendSerialized<ClientMessage>(ClientMessage.Command(id, action, turnId, checkId, text, sessionId, targetPlayerId, narrationId, scenarioId, character, useRacialTalent, combatAction, combatTarget, soloTestMode))
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

    /** Confirmed intent, independent of gameplay busy. It is successful only after Left/CLOSED. */
    fun requestLeave() {
        if (state.value.leavePending) return
        val session = socket
        if (session == null || state.value.status != ConnectionStatus.CONNECTED) {
            mutableState.value = state.value.copy(error = "Чтобы подтвердить выход из комнаты, восстановите связь с сервером. Подтверждение выхода не получено.",
                leaveAcknowledged = false, canResumeSession = resumeTicket != null)
            return
        }
        val current = generation
        timeoutJob?.cancel()
        pendingId = null
        mutableState.value = state.value.copy(leavePending = true, leaveAcknowledged = false, busy = false, error = null)
        leaveTimeoutJob = scope.launch {
            delay(15_000)
            if (current == generation && socket === session && state.value.leavePending) {
                detach(state.value.copy(status = ConnectionStatus.DISCONNECTED, leavePending = false, busy = false,
                    canResumeSession = resumeTicket != null, error = "Сервер не подтвердил выход. Восстановите связь, чтобы узнать состояние комнаты."))
            }
        }
        scope.launch {
            if (current != generation || socket !== session) return@launch
            try { session.sendSerialized<ClientMessage>(ClientMessage.Leave) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current == generation) detach(state.value.copy(status = ConnectionStatus.DISCONNECTED,
                    leavePending = false, leaveAcknowledged = false, busy = false, canResumeSession = resumeTicket != null,
                    error = "Не удалось подтвердить выход. Проверьте связь с сервером."))
            }
        }
    }

    /** Lobby/completed detachment only. An active or unknown restored party requires requestLeave(). */
    fun leave() {
        val lifecycle = state.value.room?.lifecycle
        if (lifecycle == RoomLifecycle.ACTIVE || lifecycle == RoomLifecycle.PAUSED ||
            (lifecycle == null && resumeTicket != null)) {
            mutableState.value = state.value.copy(error = "Восстановите подключение к прежней комнате и подтвердите выход через сервер.")
            return
        }
        val issue = clearTicket()
        endpoint = ""
        detach(NetworkState(error = issue))
    }

    /** Application termination is accidental transport loss, not an explicit departure. */
    fun close() {
        detach(state.value.copy(status = ConnectionStatus.DISCONNECTED, busy = false, leavePending = false,
            leaveAcknowledged = false, canResumeSession = resumeTicket != null))
        http.close()
    }

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
