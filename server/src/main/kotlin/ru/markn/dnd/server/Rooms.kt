package ru.markn.dnd.server

import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.*
import ru.markn.dnd.network.*
import kotlin.time.TimeSource

/** All commands and dice execute under one lock, never in a retrying StateFlow.update block. */
class Rooms(
    private val gameMaster: GameMaster? = null,
    private val aiScope: CoroutineScope? = null,
    private val aiTimeoutMillis: Long = 45_000,
    maxAiRequests: Int = 4,
    private val presentationEnabled: Boolean = true,
    private val playbackClock: () -> Long = ::storyNowMillis,
    private val speechPreparer: RoomSpeechPreparer? = null,
    private val speechScope: CoroutineScope? = aiScope,
    private val engineFactory: () -> AdventureEngine = { AdventureEngine(logger = GameLogger.Platform) },
) {
    init { require(gameMaster == null || aiScope != null); require(aiTimeoutMillis > 0); require(speechPreparer == null || speechScope != null) }
    private val aiSlots = Semaphore(maxAiRequests)
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val rooms = mutableMapOf<String, Room>()

    internal class Member(var player: Player, val token: String, var connectionId: String?) {
        var replaced = CompletableDeferred<Unit>()
        var characterConfirmed = false
    }
    internal data class AiCompletion(val decision: GameMasterDecision? = null, val narration: String? = null,
        val failureFields: Map<String, String> = emptyMap(), val elapsedMillis: Long)
    internal data class BufferedSpeech(val work: RoomSpeechPreparation, val audio: RoomSpeechAudio?, val completedAt: Long)
    internal data class BufferedAck(val playerId: String, val connectionId: String, val acknowledgement: RoomSpeechAcknowledgement)
    internal class AiWork(val request: PendingNarration, var state: AdventureState, val text: String,
        val outcome: OutcomeNarration? = null, val participation: ParticipationSnapshot? = null) {
        var job: Job? = null
        var inputPresentation: List<InputUtterance>? = null
        var previewPublished = false
        var completion: AiCompletion? = null
    }
    internal class Room(val code: String, val engine: AdventureEngine, val gameMasterEnabled: Boolean,
        val presentationEnabled: Boolean, val playbackClock: () -> Long, val speech: RoomSpeechCoordinator? = null) {
        val members = mutableListOf<Member>()
        var speechTimer: Job? = null
        val speechJobs = mutableMapOf<Pair<String, String>, Job>()
        var hostId = ""
        var lifecycle = RoomLifecycle.LOBBY
        var pausedAtMillis: Long? = null
        var closedByPlayerId: String? = null
        val bufferedSpeech = mutableMapOf<Pair<String, String>, BufferedSpeech>()
        val bufferedAcks = linkedSetOf<BufferedAck>()
        private var storedAdventure: AdventureState? = null
        var adventure: AdventureState?
            get() = storedAdventure
            set(value) {
                storedAdventure = if (presentationEnabled)
                    value?.schedulePlayback(storedAdventure, pausedAtMillis ?: playbackClock()) else value
            }
        /** Resume only rebases an existing timeline; scheduling would mutate its actor/preview/speeches. */
        fun shiftPlaybackTimeline(delta: Long) {
            storedAdventure = storedAdventure?.let { state ->
                state.copy(playback = state.playback.shiftTimeline(delta))
            }
        }
        var revision = 0L
        var lastActivity = System.currentTimeMillis()
        var pendingAi: AiWork? = null
        var gameMasterError: String? = null
        var scenarioId: String = Lighthouse.ID
        val activities = mutableListOf<ConfirmedActivity>()
        val participation = ParticipationMemory()
        val updates = MutableStateFlow(RoomView(code, "", emptyList()))
        fun publish(): RoomView {
            revision++
            lastActivity = System.currentTimeMillis()
            // Keep wire frames bounded during long sessions. Canonical state stays on the server.
            return RoomView(code, hostId, members.map { RoomMember(it.player, it.connectionId != null, it.characterConfirmed) },
                adventure?.publicRoomSnapshot(), revision,
                gameMasterEnabled, pendingAi?.request, gameMasterError, scenarioId, playbackClock(), speech?.view,
                lifecycle = lifecycle, waitingPlayerIds = if (lifecycle == RoomLifecycle.PAUSED)
                    members.filter { it.connectionId == null }.map { it.player.id } else emptyList(),
                pausedAtMillis = pausedAtMillis, closedByPlayerId = closedByPlayerId).also { updates.value = it }
        }
    }
    class Connection internal constructor(internal val room: Room, internal val member: Member, internal val id: String, val replaced: CompletableDeferred<Unit>) {
        internal var departed = false
        var lastRequestId = 0L
            internal set
        val updates get() = room.updates
        val playerId get() = member.player.id
    }
    data class Admission(val connection: Connection, val welcome: ServerMessage.Welcome)

    suspend fun join(message: ClientMessage.Join): Admission = mutex.withLock {
        if (message.version != PROTOCOL_VERSION) throw RoomConnectionRejected(ConnectionRejection.VERSION_MISMATCH,
            "Версия игры не совпадает с сервером.")
        val now = System.currentTimeMillis()
        rooms.entries.removeAll { (_, room) ->
            room.lifecycle in setOf(RoomLifecycle.LOBBY, RoomLifecycle.COMPLETED, RoomLifecycle.CLOSED) &&
                room.members.none { it.connectionId != null } && now - room.lastActivity > 30 * 60_000
        }
        val code = message.roomCode.trim().uppercase()
        val room: Room
        val member: Member
        if (message.resumeToken != null) {
            room = rooms[code] ?: throw RoomConnectionRejected(ConnectionRejection.ROOM_UNAVAILABLE, "Комната больше не существует.")
            if (room.lifecycle == RoomLifecycle.CLOSED) throw RoomConnectionRejected(ConnectionRejection.ROOM_UNAVAILABLE, "Эта партия уже завершена участником.")
            member = room.members.firstOrNull { it.token == message.resumeToken }
                ?: throw RoomConnectionRejected(ConnectionRejection.RESUME_REJECTED, "Не удалось восстановить подключение.")
        } else {
            val name = message.name.trim()
            require(name.length in 1..24 && name.none { it.isISOControl() }) { "Имя должно содержать от 1 до 24 символов." }
            if (code.isEmpty()) {
                require(rooms.size < 100) { "Сервер заполнен. Попробуйте позже." }
                var generated: String
                do { generated = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("") }
                while (generated in rooms)
                room = Room(generated, engineFactory(), gameMaster != null, presentationEnabled, playbackClock,
                    if (presentationEnabled && speechPreparer != null) RoomSpeechCoordinator() else null)
                rooms[generated] = room
            } else {
                room = rooms[code] ?: throw RoomConnectionRejected(ConnectionRejection.ROOM_UNAVAILABLE, "Комната не найдена.")
                if (room.lifecycle == RoomLifecycle.CLOSED) throw RoomConnectionRejected(ConnectionRejection.ROOM_UNAVAILABLE, "Эта партия уже завершена участником.")
            }
            require(room.adventure == null) { "Приключение уже началось. Новые игроки пока не могут войти." }
            require(room.members.size < 4) { "В комнате уже четыре игрока." }
            require(room.members.none { it.player.name.equals(name, ignoreCase = true) }) { "Это имя уже занято в комнате." }
            val scenario = requireNotNull(Scenarios.find(room.scenarioId))
            val profile = ReadyCharacters.all.firstOrNull { profile -> room.members.none { it.player.hero.name.equals(profile.name, true) } }
                ?: ReadyCharacters.rin.copy(name = (1..100).map { "Герой $it" }.first { name -> room.members.none { it.player.hero.name.equals(name, true) } })
            val template = Player("new", name, profile.hero(scenario), scenario.startLocation)
            member = Member(template.copy(id = UUID.randomUUID().toString(), name = name), UUID.randomUUID().toString(), null)
            room.members += member
        }
        val id = UUID.randomUUID().toString()
        member.replaced.complete(Unit)
        member.replaced = CompletableDeferred()
        member.connectionId = id
        if (message.resumeToken != null && room.adventure?.ending == null) {
            room.speech?.forgetParticipantAcknowledgements(member.player.id)
            room.speech?.awaitParticipantRecovery(member.player.id, room.pausedAtMillis ?: playbackClock())
        }
        if (room.members.none { it.player.id == room.hostId && it.connectionId != null }) room.hostId = member.player.id
        val view = publishRoom(room)
        networkLog("network_player_connected", room, member.player.id)
        Admission(Connection(room, member, id, member.replaced), ServerMessage.Welcome(member.player.id, member.token, view))
    }

    suspend fun disconnect(connection: Connection) = mutex.withLock {
        val (room, member) = connection.room to connection.member
        if (member.connectionId == connection.id) {
            member.connectionId = null
            if (room.adventure?.ending == null) room.speech?.forgetParticipantAcknowledgements(member.player.id)
            if (room.hostId == member.player.id) {
                room.members.firstOrNull { it.connectionId != null }?.let { room.hostId = it.player.id }
            }
            publishRoom(room)
            networkLog("network_player_disconnected", room, member.player.id)
        }
    }

    suspend fun command(connection: Connection, message: ClientMessage.Command): ServerMessage.Reply = mutex.withLock {
        val room = connection.room
        val previousState = room.adventure
        val actor = connection.member.player.id
        updateLifecycle(room)
        if (room.lifecycle in setOf(RoomLifecycle.PAUSED, RoomLifecycle.CLOSED)) {
            return@withLock ServerMessage.Reply(message.requestId, if (room.lifecycle == RoomLifecycle.PAUSED)
                "Игра приостановлена: ожидаем возвращения всех участников." else "Эта партия завершена участником.", room.updates.value)
        }
        refreshPresentation(room)
        val presentationBusy = if (room.speech != null) room.speech.view?.busy == true
            else previousState != null && playbackClock() < previousState.playback.busyUntil
        if (presentationEnabled && presentationBusy &&
            message.action !in listOf(NetworkAction.RESTART, NetworkAction.CANCEL_NARRATION)) {
            return@withLock ServerMessage.Reply(message.requestId, "Дождитесь завершения реплики.", room.updates.value)
        }
        var error: String? = null
        if (connection.member.connectionId != connection.id) error = "Подключение заменено. Подключитесь снова."
        else if (message.requestId <= connection.lastRequestId) error = "Повторная команда отклонена."
        else {
            connection.lastRequestId = message.requestId
            val state = room.adventure
            val host = actor == room.hostId
            when (message.action) {
                NetworkAction.SELECT_CHARACTER -> {
                    val profile = message.character
                    val occupied = room.members.firstOrNull { it.player.id != actor && it.player.hero.name.equals(profile?.name, true) }
                    error = when {
                        state != null -> "Героя можно выбрать только до начала истории."
                        profile == null -> "Выберите персонажа."
                        profile.validationError() != null -> profile.validationError()
                        occupied?.characterConfirmed == true -> "Герой с таким именем уже выбран. Выберите другое имя или героя."
                        else -> null
                    }
                    if (error == null) {
                        // Initial suggestions do not reserve presets. Swap only an unclaimed suggestion.
                        occupied?.let { it.player = it.player.copy(hero = connection.member.player.hero) }
                        connection.member.player = connection.member.player.copy(
                            hero = requireNotNull(profile).hero(requireNotNull(Scenarios.find(room.scenarioId))))
                        connection.member.characterConfirmed = true
                    }
                }
                NetworkAction.SELECT_SCENARIO -> {
                    val scenario = message.scenarioId?.let(Scenarios::find)
                    if (!host || state != null || scenario == null) error = "Сюжет выбирает ведущий комнаты до начала игры."
                    else {
                        room.scenarioId = scenario.id
                        room.members.forEach { member -> member.player = member.player.copy(location = scenario.startLocation,
                            hero = member.player.hero.copy(inventory = scenario.startingItems)) }
                    }
                }
                NetworkAction.CANCEL_NARRATION -> {
                    val work = room.pendingAi
                    if (work == null || work.request.id != message.narrationId ||
                        work.request.turnId != message.turnId || work.request.playerId != actor) {
                        error = "Этот ответ ведущего уже недоступен для отмены."
                    } else cancelAi(room)
                }
                NetworkAction.START -> {
                    if (!host || state != null || room.members.size < 2 || room.members.any { it.connectionId == null }) {
                        error = "Начать может создатель комнаты, когда подключены 2–4 игрока."
                    } else if (room.members.any { !it.characterConfirmed }) {
                        error = "Каждый игрок должен подтвердить своего героя перед началом истории."
                    } else {
                        room.activities.clear()
                        room.adventure = room.engine.newGame(players = room.members.map { it.player }, scenarioId = room.scenarioId)
                        room.participation.reset(requireNotNull(room.adventure))
                    }
                }
                NetworkAction.RESTART -> {
                    if (!host || state == null || state.sessionId != message.sessionId || room.members.any { it.connectionId == null })
                        error = "Перезапуск доступен только при подключении всех участников."
                    else {
                        cancelAi(room)
                        room.gameMasterError = null
                        room.activities.clear()
                        room.adventure = room.engine.newGame(state.sessionId + 1, room.members.map { it.player }, room.scenarioId)
                        room.participation.reset(requireNotNull(room.adventure))
                    }
                }
                NetworkAction.REMOVE_DISCONNECTED -> {
                    val target = room.members.firstOrNull { it.player.id == message.targetPlayerId }
                    if (!host || state != null || target == null || target.connectionId != null) {
                        error = "Из лобби можно убрать только отключившегося участника."
                    } else room.members.remove(target)
                }
                NetworkAction.SKIP_DISCONNECTED -> {
                    error = "Ход отключённого игрока нельзя пропустить. Ожидаем его возвращения."
                }
                else -> {
                    if (state == null) error = "Приключение ещё не началось."
                    else if (room.pendingAi != null) error = "Дождитесь ответа ведущего или отмените запрос."
                    else if (message.action == NetworkAction.SUBMIT && gameMaster != null &&
                        state.scenario.actions.none { it.plan != null && it.text.equals(message.text.trim().trimEnd('.', '!', '?'), ignoreCase = true) }) {
                        if (!state.canAct || state.activePlayer.id != actor || state.turnId != message.turnId ||
                            message.text.trim().length !in 1..AdventureEngine.MAX_ACTION_LENGTH) {
                            error = "Действие недоступно: проверьте текст и дождитесь своего хода."
                        } else if (!aiSlots.tryAcquire()) {
                            error = "Ведущий занят. Попробуйте отправить действие немного позже."
                        } else {
                            val work = AiWork(PendingNarration(UUID.randomUUID().toString(), actor, state.turnId), requireNotNull(room.adventure), message.text.trim(),
                                participation = room.participation.snapshot(state,
                                    room.members.filter { it.connectionId != null }.map { it.player.id }.toSet()))
                            room.gameMasterError = null
                            room.pendingAi = work
                            val job = requireNotNull(aiScope).launch(start = CoroutineStart.LAZY) { runAi(room, work) }
                            work.job = job
                            job.invokeOnCompletion { aiSlots.release() }
                            job.start()
                        }
                    }
                    else {
                        val next = when (message.action) {
                            NetworkAction.COMBAT -> message.turnId?.let { room.engine.combatCommand(state, actor, it, message.combatAction, message.combatTarget) }
                            NetworkAction.SUBMIT -> message.turnId?.let { room.engine.submit(state, actor, it, message.text) }
                            NetworkAction.PASS -> message.turnId?.let { room.engine.endTurn(state, actor, it) }
                            NetworkAction.ROLL -> message.checkId?.let { room.engine.roll(state, actor, it, message.useRacialTalent) }
                            NetworkAction.CANCEL -> message.checkId?.let { room.engine.cancelCheck(state, actor, it) }
                            else -> null
                        }
                        if (next == null || next == state) error = "Действие недоступно: дождитесь своего хода и актуального состояния."
                        else room.adventure = next
                    }
                }
            }
        }
        val view = if (error == null) {
            room.gameMasterError = null
            val current = room.adventure
            if (previousState != null && current != null && current !== previousState &&
                message.action !in listOf(NetworkAction.START, NetworkAction.RESTART)) {
                room.participation.record(previousState, current, message.action)
                recordOutcome(room, previousState, current, actor, message.action == NetworkAction.ROLL)
            }
            publishRoom(room)
        } else room.updates.value
        networkLog(if (error == null) "network_command_completed" else "network_command_rejected", room, actor, message.action.name)
        ServerMessage.Reply(message.requestId, error, view)
    }

    private fun cancelAi(room: Room) {
        val work = room.pendingAi
        room.pendingAi = null
        work?.job?.cancel()
        clearSpeechPreview(room)
    }

    private fun clearSpeechPreview(room: Room) {
        val state = room.adventure ?: return
        if (state.playback.previewEntryId != null) {
            room.speech?.discardUnstartedPreview(state.playback.previewEntryIds.toSet(), playbackClock())
            room.adventure = state.copy(playback = state.playback.copy(previewEntryId = null, previewEntryIds = emptyList()))
        }
    }

    private suspend fun runAi(room: Room, work: AiWork) {
        val started = TimeSource.Monotonic.markNow()
        var failureFields = emptyMap<String, String>()
        val decision = try {
            withTimeout(aiTimeoutMillis) {
                requireNotNull(gameMaster).respond(work.state, work.text, requireNotNull(work.participation)) { parts ->
                    validateInputPresentation(parts)
                    mutex.withLock {
                        if (room.pendingAi === work && room.adventure === work.state && work.inputPresentation == null) {
                            work.inputPresentation = parts.toList()
                            if (room.lifecycle != RoomLifecycle.PAUSED) {
                                applyPreview(room, work)
                                publishRoom(room)
                            }
                            networkLog("ai_input_presentation_ready", room, work.request.playerId, details = mapOf(
                                "request_id" to work.request.id,
                                "elapsed_ms" to started.elapsedNow().inWholeMilliseconds.toString(),
                                "parts" to parts.size.toString(),
                                "kinds" to parts.joinToString(",") { it.kind.name }))
                        }
                    }
                }.also { decision ->
                    if (work.inputPresentation != null && decision.inputPresentation != work.inputPresentation)
                        throw AiFailure("input_presentation_changed")
                }
            }
        } catch (_: TimeoutCancellationException) {
            failureFields = mapOf("reason" to "request_timeout")
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Provider errors may contain private request data; never publish or log their messages.
            failureFields = if (failure is AiFailure) failure.fields() else mapOf("reason" to "unexpected_error")
            null
        }
        mutex.withLock {
            if (room.pendingAi !== work) return@withLock
            work.completion = AiCompletion(decision = decision, failureFields = failureFields,
                elapsedMillis = started.elapsedNow().inWholeMilliseconds)
            if (room.lifecycle != RoomLifecycle.PAUSED) { applyCompletion(room, work); publishRoom(room) }
        }
    }

    private fun applyPreview(room: Room, work: AiWork) {
        if (!presentationEnabled || work.previewPublished || room.pendingAi !== work || room.adventure !== work.state) return
        val parts = work.inputPresentation ?: return
        room.adventure = work.state.previewInputPresentation(parts, playbackClock())
        work.state = requireNotNull(room.adventure)
        work.previewPublished = true
    }

    private fun applyCompletion(room: Room, work: AiWork) {
        val completion = work.completion ?: return
        if (room.pendingAi !== work) return
        applyPreview(room, work)
        room.pendingAi = null
        if (room.adventure !== work.state) return
        val failureFields = completion.failureFields
        if (work.outcome != null) {
            val narration = completion.narration
            if (narration == null) room.gameMasterError = if (work.request.kind == NarrationKind.EPILOGUE)
                "Итог истории сохранён. Ведущий не смог подготовить эпилог."
                else "Результат сохранён. Ведущий не смог подготовить описание; можно продолжать игру."
            else {
                val actor = work.state.players.first { it.id == work.request.playerId }
                room.adventure = work.state.copy(journal = work.state.journal + JournalEntry(
                    (work.state.journal.lastOrNull()?.id ?: 0) + 1, EntryKind.NARRATOR, narration,
                    playerId = actor.id, actorName = actor.hero.name))
            }
            networkLog(if (narration == null) "ai_outcome_failed" else "ai_outcome_completed", room, work.request.playerId,
                details = failureFields + mapOf("request_id" to work.request.id,
                    "kind" to work.request.kind.name, "elapsed_ms" to completion.elapsedMillis.toString()))
            return
        }
        val decision = completion.decision
            if (decision == null) {
                clearSpeechPreview(room)
                room.gameMasterError = if (failureFields["http_status"] == "401")
                    "Сервис ИИ отклонил авторизацию. Проверьте API-ключ на сервере. Ход сохранён."
                else when (failureFields["reason"]) {
                    "transport_timeout", "request_timeout" -> "Сервис ИИ не ответил вовремя. Ход сохранён. Можно повторить отправку или выбрать готовое действие."
                    "connection_error", "dns_error", "tls_error" -> "Не удалось связаться с сервисом ИИ. Ход сохранён. Готовые действия доступны."
                    else -> "Сервис ИИ вернул некорректный ответ или ошибку. Ход сохранён — отправьте действие снова."
                }
                networkLog("ai_request_failed", room, work.request.playerId, details = failureFields + mapOf(
                    "request_id" to work.request.id, "elapsed_ms" to completion.elapsedMillis.toString()))
            } else {
                val next = room.engine.submit(work.state, work.request.playerId, work.request.turnId, work.text, decision)
                if (next == work.state) {
                    clearSpeechPreview(room)
                    room.gameMasterError = "План ведущего не соответствует состоянию или правилам сцены. Ход сохранён — уточните действие."
                } else {
                    room.adventure = next
                    room.participation.record(work.state, next, NetworkAction.SUBMIT)
                    recordOutcome(room, work.state, requireNotNull(room.adventure), work.request.playerId, false)
                }
                networkLog(if (next == work.state) "ai_plan_rejected" else "ai_request_completed", room, work.request.playerId, details = mapOf(
                    "request_id" to work.request.id, "elapsed_ms" to completion.elapsedMillis.toString()))
            }
    }

    /** Called under the mutex, after the engine committed. Capacity failure must never reject a roll. */
    private fun recordOutcome(room: Room, before: AdventureState, after: AdventureState, actor: String, rolled: Boolean) {
        val activity = confirmedActivity(before, after, actor) ?: return
        room.activities += activity
        // Known scenario effects already contain a short authored description. Avoid a second model round trip.
        if (rolled && before.pendingCheck?.action != null && before.pendingCheck?.nextPlayerId == null && after.ending == null) return
        if (gameMaster !is OutcomeNarrator || (!rolled && !(before.ending == null && after.ending != null))) return
        if (!aiSlots.tryAcquire()) {
            room.gameMasterError = "Результат сохранён. Ведущий занят и не смог подготовить описание."
            return
        }
        val kind = if (after.ending != null) NarrationKind.EPILOGUE else NarrationKind.REACTION
        val work = AiWork(PendingNarration(UUID.randomUUID().toString(), actor, after.turnId, kind), after, "",
            OutcomeNarration(before, after, activity, room.activities.toList()))
        room.pendingAi = work
        val job = requireNotNull(aiScope).launch(start = CoroutineStart.LAZY) { runOutcome(room, work) }
        work.job = job
        job.invokeOnCompletion { aiSlots.release() }
        job.start()
    }

    private suspend fun runOutcome(room: Room, work: AiWork) {
        val started = TimeSource.Monotonic.markNow()
        var failureFields = emptyMap<String, String>()
        val narration = try {
            withTimeout(aiTimeoutMillis) {
                (gameMaster as OutcomeNarrator).narrate(requireNotNull(work.outcome)).also {
                    if (it.isBlank() || it.length > 2_000) throw AiFailure("outcome_narration_invalid")
                }
            }
        } catch (_: TimeoutCancellationException) {
            failureFields = mapOf("reason" to "request_timeout")
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            failureFields = if (failure is AiFailure) failure.fields() else mapOf("reason" to "unexpected_error")
            null
        }
        mutex.withLock {
            if (room.pendingAi !== work) return@withLock
            work.completion = AiCompletion(narration = narration, failureFields = failureFields,
                elapsedMillis = started.elapsedNow().inWholeMilliseconds)
            if (room.lifecycle != RoomLifecycle.PAUSED) { applyCompletion(room, work); publishRoom(room) }
        }
    }

    /** ACKs are authenticated by connection, never consume gameplay request IDs, and never mutate adventure. */
    suspend fun speechAck(connection: Connection, acknowledgement: RoomSpeechAcknowledgement) = mutex.withLock {
        if (connection.member.connectionId != connection.id) return@withLock
        val room = connection.room
        if (room.lifecycle == RoomLifecycle.CLOSED) return@withLock
        if (room.lifecycle == RoomLifecycle.PAUSED) {
            // In-flight acknowledgements may have been sent before the client saw PAUSED.
            // Bound storage to current cues/roster; replay only this connection on resume.
            val view = room.speech?.view
            if (view?.sessionId == acknowledgement.sessionId && view.batchId == acknowledgement.batchId &&
                view.cues.any { it.id == acknowledgement.cueId }) {
                room.bufferedAcks += BufferedAck(connection.playerId, connection.id, acknowledgement)
            }
            return@withLock
        }
        if (room.speech?.acknowledge(connection.playerId, acknowledgement, playbackClock()) == true) publishRoom(room)
    }

    /** All callers hold mutex. Launching jobs uses normal dispatch, never undispatched execution/IO. */
    private fun refreshPresentation(room: Room) {
        if (room.lifecycle in setOf(RoomLifecycle.PAUSED, RoomLifecycle.CLOSED)) {
            room.speechTimer?.cancel(); room.speechTimer = null
            return
        }
        val speech = room.speech ?: return
        val state = room.adventure ?: return
        val now = playbackClock()
        speech.observe(state, room.members.filter { it.connectionId != null }.map { it.player.id }.toSet(), room.pendingAi != null, now)
        val current = speech.view
        val pending = current?.cues.orEmpty().filter { it.source == RoomSpeechSource.GENERATED && it.audioId == null }
            .map { requireNotNull(current).batchId to it.id }.toSet()
        room.speechJobs.keys.toList().filter { it !in pending }.forEach { room.speechJobs.remove(it)?.cancel() }
        for (work in speech.takePreparations(now)) {
            val key = work.batchId to work.cueId
            room.speechJobs[key] = requireNotNull(speechScope).launch(Dispatchers.Default) {
                val audio = try { requireNotNull(speechPreparer).prepare(work.text, work.voice) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                mutex.withLock {
                    room.speechJobs.remove(key)
                    if (room.lifecycle == RoomLifecycle.PAUSED) {
                        room.bufferedSpeech[key] = BufferedSpeech(work, audio, requireNotNull(room.pausedAtMillis))
                    } else if (room.lifecycle != RoomLifecycle.CLOSED &&
                        speech.prepared(work.batchId, work.cueId, audio, playbackClock())) publishRoom(room)
                }
            }
        }
        room.speechTimer?.cancel()
        room.speechTimer = speech.nextWakeAt(now)?.let { deadline -> requireNotNull(speechScope).launch(Dispatchers.Default) {
            delay((deadline - playbackClock()).coerceAtLeast(1))
            val timer = currentCoroutineContext()[Job]
            mutex.withLock {
                // A cancelled/replaced timer cannot clear the new timer or publish an old tick.
                if (room.speechTimer !== timer) return@withLock
                room.speechTimer = null
                publishRoom(room)
            }
        } }
    }

    /** Explicit departure is independent of gameplay busy/request IDs. */
    suspend fun leave(connection: Connection) = mutex.withLock {
        val room = connection.room
        val member = connection.member
        check(!connection.departed && member.connectionId == connection.id) { "Подключение заменено." }
        connection.departed = true
        member.connectionId = null
        when (room.lifecycle) {
            RoomLifecycle.LOBBY -> room.members.remove(member)
            RoomLifecycle.ACTIVE, RoomLifecycle.PAUSED -> {
                room.lifecycle = RoomLifecycle.CLOSED
                room.closedByPlayerId = member.player.id
                room.pausedAtMillis = null
                cancelAi(room)
                room.speech?.closePresentation()
                room.speechTimer?.cancel(); room.speechTimer = null
                room.speechJobs.values.forEach { it.cancel() }; room.speechJobs.clear()
                room.bufferedSpeech.clear(); room.bufferedAcks.clear()
            }
            RoomLifecycle.COMPLETED, RoomLifecycle.CLOSED -> Unit
        }
        if (room.hostId == member.player.id) room.members.firstOrNull { it.connectionId != null }?.let { room.hostId = it.player.id }
        publishRoom(room)
    }

    private fun updateLifecycle(room: Room) {
        if (room.lifecycle == RoomLifecycle.CLOSED) return
        val state = room.adventure
        if (state == null) { room.lifecycle = RoomLifecycle.LOBBY; return }
        if (state.ending != null) { room.lifecycle = RoomLifecycle.COMPLETED; return }
        if (room.members.any { it.connectionId == null }) {
            if (room.pausedAtMillis == null) room.pausedAtMillis = playbackClock()
            room.lifecycle = RoomLifecycle.PAUSED
            room.speechTimer?.cancel(); room.speechTimer = null
            return
        }
        val pausedAt = room.pausedAtMillis
        room.lifecycle = RoomLifecycle.ACTIVE
        if (pausedAt == null) return
        val delta = (playbackClock() - pausedAt).coerceAtLeast(0)
        room.pausedAtMillis = null
        room.speech?.shiftTimeline(delta)
        if (delta > 0) room.shiftPlaybackTimeline(delta)
        room.pendingAi?.let { if (it.state === state) it.state = requireNotNull(room.adventure) }
        val prepared = room.bufferedSpeech.values.toList()
        room.bufferedSpeech.clear()
        prepared.forEach { result -> room.speech?.prepared(result.work.batchId, result.work.cueId, result.audio,
            result.completedAt + delta) }
        room.pendingAi?.let { applyPreview(room, it) }
        val acknowledgements = room.bufferedAcks.toList()
        room.bufferedAcks.clear()
        acknowledgements.filter { ack -> room.members.any { it.player.id == ack.playerId && it.connectionId == ack.connectionId } }
            .forEach { room.speech?.acknowledge(it.playerId, it.acknowledgement, playbackClock()) }
        room.pendingAi?.let { applyCompletion(room, it) }
        if (room.adventure?.ending != null) room.lifecycle = RoomLifecycle.COMPLETED
    }

    private fun publishRoom(room: Room): RoomView {
        updateLifecycle(room)
        refreshPresentation(room)
        return room.publish()
    }

    private fun networkLog(event: String, room: Room, playerId: String, action: String = "", details: Map<String, String> = emptyMap()) {
        try {
            GameLogger.Platform.log(GameLogEvent(event, mapOf("room" to room.code, "playerId" to playerId, "action" to action) + details))
        } catch (_: Exception) { /* Diagnostics must never affect the game. */ }
    }
}

/** Journal events are the network presentation source; keep the authority's complete battle history private. */
internal fun AdventureState.publicRoomSnapshot(): AdventureState = copy(
    journal = journal.takeLast(200),
    combat = combat?.copy(events = emptyList(), diceEvents = emptyList()),
)

internal class RoomConnectionRejected(val reason: ConnectionRejection, message: String) : IllegalStateException(message)

private fun StoryPlayback.shiftTimeline(delta: Long): StoryPlayback {
    if (delta <= 0) return this
    fun shift(value: Long) = if (value == 0L) value else value + delta
    return copy(openingAt = shift(openingAt), openingEndsAt = shift(openingEndsAt),
        endingAt = shift(endingAt), endingEndsAt = shift(endingEndsAt),
        speeches = speeches.map { it.copy(startsAt = it.startsAt + delta, endsAt = it.endsAt + delta) })
}
