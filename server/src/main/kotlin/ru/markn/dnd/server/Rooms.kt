package ru.markn.dnd.server

import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.*
import ru.markn.dnd.network.*

/** All commands and dice execute under one lock, never in a retrying StateFlow.update block. */
class Rooms(private val engineFactory: () -> AdventureEngine = { AdventureEngine(logger = GameLogger.Platform) }) {
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val rooms = mutableMapOf<String, Room>()

    internal class Member(val player: Player, val token: String, var connectionId: String?) {
        var replaced = CompletableDeferred<Unit>()
    }
    internal class Room(val code: String, val engine: AdventureEngine) {
        val members = mutableListOf<Member>()
        var hostId = ""
        var adventure: AdventureState? = null
        var revision = 0L
        var lastActivity = System.currentTimeMillis()
        val updates = MutableStateFlow(RoomView(code, "", emptyList()))
        fun publish(): RoomView {
            revision++
            lastActivity = System.currentTimeMillis()
            // Keep wire frames bounded during long sessions. Canonical state stays on the server.
            return RoomView(code, hostId, members.map { RoomMember(it.player, it.connectionId != null) },
                adventure?.let { it.copy(journal = it.journal.takeLast(200)) }, revision).also { updates.value = it }
        }
    }
    class Connection internal constructor(internal val room: Room, internal val member: Member, internal val id: String, val replaced: CompletableDeferred<Unit>) {
        var lastRequestId = 0L
            internal set
        val updates get() = room.updates
        val playerId get() = member.player.id
    }
    data class Admission(val connection: Connection, val welcome: ServerMessage.Welcome)

    suspend fun join(message: ClientMessage.Join): Admission = mutex.withLock {
        require(message.version == PROTOCOL_VERSION) { "Версия игры не совпадает с сервером." }
        val now = System.currentTimeMillis()
        rooms.entries.removeAll { (_, room) ->
            room.members.none { it.connectionId != null } && now - room.lastActivity > 30 * 60_000
        }
        val code = message.roomCode.trim().uppercase()
        val room: Room
        val member: Member
        if (message.resumeToken != null) {
            room = rooms[code] ?: error("Комната больше не существует.")
            member = room.members.firstOrNull { it.token == message.resumeToken }
                ?: error("Не удалось восстановить подключение.")
        } else {
            val name = message.name.trim()
            require(name.length in 1..24 && name.none { it.isISOControl() }) { "Имя должно содержать от 1 до 24 символов." }
            if (code.isEmpty()) {
                require(rooms.size < 100) { "Сервер заполнен. Попробуйте позже." }
                var generated: String
                do { generated = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("") }
                while (generated in rooms)
                room = Room(generated, engineFactory())
                rooms[generated] = room
            } else {
                room = rooms[code] ?: error("Комната не найдена.")
            }
            require(room.adventure == null) { "Приключение уже началось. Новые игроки пока не могут войти." }
            require(room.members.size < 3) { "В комнате уже три игрока." }
            require(room.members.none { it.player.name.equals(name, ignoreCase = true) }) { "Это имя уже занято в комнате." }
            val template = AdventureEngine.demoPlayers().first { hero -> room.members.none { it.player.hero.name == hero.hero.name } }
            member = Member(template.copy(id = UUID.randomUUID().toString(), name = name), UUID.randomUUID().toString(), null)
            room.members += member
        }
        val id = UUID.randomUUID().toString()
        member.replaced.complete(Unit)
        member.replaced = CompletableDeferred()
        member.connectionId = id
        if (room.members.none { it.player.id == room.hostId && it.connectionId != null }) room.hostId = member.player.id
        val view = room.publish()
        networkLog("network_player_connected", room, member.player.id)
        Admission(Connection(room, member, id, member.replaced), ServerMessage.Welcome(member.player.id, member.token, view))
    }

    suspend fun disconnect(connection: Connection) = mutex.withLock {
        val (room, member) = connection.room to connection.member
        if (member.connectionId == connection.id) {
            member.connectionId = null
            if (room.hostId == member.player.id) {
                room.members.firstOrNull { it.connectionId != null }?.let { room.hostId = it.player.id }
            }
            room.publish()
            networkLog("network_player_disconnected", room, member.player.id)
        }
    }

    suspend fun command(connection: Connection, message: ClientMessage.Command): ServerMessage.Reply = mutex.withLock {
        val room = connection.room
        val actor = connection.member.player.id
        var error: String? = null
        if (connection.member.connectionId != connection.id) error = "Подключение заменено. Подключитесь снова."
        else if (message.requestId <= connection.lastRequestId) error = "Повторная команда отклонена."
        else {
            connection.lastRequestId = message.requestId
            val state = room.adventure
            val host = actor == room.hostId
            when (message.action) {
                NetworkAction.START -> {
                    if (!host || state != null || room.members.size < 2 || room.members.any { it.connectionId == null }) {
                        error = "Начать может создатель комнаты, когда подключены 2–3 игрока."
                    } else room.adventure = room.engine.newGame(players = room.members.map { it.player })
                }
                NetworkAction.RESTART -> {
                    if (!host || state == null || state.sessionId != message.sessionId) error = "Перезапуск недоступен."
                    else room.adventure = room.engine.newGame(state.sessionId + 1, room.members.map { it.player })
                }
                NetworkAction.REMOVE_DISCONNECTED -> {
                    val target = room.members.firstOrNull { it.player.id == message.targetPlayerId }
                    if (!host || state != null || target == null || target.connectionId != null) {
                        error = "Из лобби можно убрать только отключившегося участника."
                    } else room.members.remove(target)
                }
                NetworkAction.SKIP_DISCONNECTED -> {
                    if (!host || state == null || state.ending != null || message.turnId != state.turnId ||
                        room.members.any { it.player.id == state.activePlayer.id && it.connectionId != null }) {
                        error = "Можно пропустить только ход отключившегося игрока."
                    } else {
                        val withoutCheck = state.pendingCheck?.let {
                            room.engine.cancelCheck(state, state.activePlayer.id, it.id)
                        } ?: state
                        room.adventure = room.engine.endTurn(withoutCheck, state.activePlayer.id, state.turnId)
                    }
                }
                else -> {
                    if (state == null) error = "Приключение ещё не началось."
                    else {
                        val next = when (message.action) {
                            NetworkAction.SUBMIT -> message.turnId?.let { room.engine.submit(state, actor, it, message.text) }
                            NetworkAction.PASS -> message.turnId?.let { room.engine.endTurn(state, actor, it) }
                            NetworkAction.ROLL -> message.checkId?.let { room.engine.roll(state, actor, it) }
                            NetworkAction.CANCEL -> message.checkId?.let { room.engine.cancelCheck(state, actor, it) }
                            else -> null
                        }
                        if (next == null || next == state) error = "Действие недоступно: дождитесь своего хода и актуального состояния."
                        else room.adventure = next
                    }
                }
            }
        }
        val view = if (error == null) room.publish() else room.updates.value
        networkLog(if (error == null) "network_command_completed" else "network_command_rejected", room, actor, message.action.name)
        ServerMessage.Reply(message.requestId, error, view)
    }

    private fun networkLog(event: String, room: Room, playerId: String, action: String = "") {
        try {
            GameLogger.Platform.log(GameLogEvent(event, mapOf("room" to room.code, "playerId" to playerId, "action" to action)))
        } catch (_: Exception) { /* Diagnostics must never affect the game. */ }
    }
}
