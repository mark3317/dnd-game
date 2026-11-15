package ru.markn.dnd.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.adventure.ui.AdventureScreen
import ru.markn.dnd.adventure.ui.ScenarioPicker
import ru.markn.dnd.adventure.CharacterLibrary
import ru.markn.dnd.adventure.ui.CharacterPicker

@Composable
fun NetworkScreen(client: NetworkGameClient, state: NetworkState, characters: CharacterLibrary,
                  creating: Boolean = true, onBack: () -> Unit = {}) {
    var address by rememberSaveable { mutableStateOf("ws://localhost:8080") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val room = state.room
    val connected = state.status == ConnectionStatus.CONNECTED
    val host = room != null && room.hostPlayerId == state.playerId
    val sessionUi = state.sessionPresentation()
    val leaveUi = state.leavePresentation()
    val paused = sessionUi.paused
    val available = sessionUi.commandsAvailable
    var awaitingLeaveAcknowledgement by remember { mutableStateOf(false) }
    LaunchedEffect(state.leaveAcknowledged, awaitingLeaveAcknowledgement) {
        if (state.leaveAcknowledged && awaitingLeaveAcknowledgement) onBack()
    }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingScenario by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var choosingStory by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(room?.code, room?.scenarioId, pendingScenario, available) {
        val target = pendingScenario
        if (room != null && host && target != null) {
            if (room.scenarioId == target) pendingScenario = null
            else if (available) { pendingScenario = null; client.command(NetworkAction.SELECT_SCENARIO, scenarioId = target) }
        }
    }
    Box(Modifier.fillMaxSize()) {
    if (room?.adventure == null) GameBackdrop {}
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (room == null) {
            Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuHeader(if (creating) "Создать игру" else "Присоединиться к игре",
                    if (creating) "Выберите историю — и соберите свой отряд" else "У каждого приключения есть место для друга", onBack = { if (!state.canResumeSession) client.leave(); onBack() })
                if (state.canResumeSession) {
                    Text("Партия ожидает вашего возвращения. Восстановите связь; история хранится только на работающем сервере.", color = AdventureMuted)
                    AdventureButton(onClick = client::reconnect, enabled = state.status != ConnectionStatus.CONNECTING) { Text("Вернуться к партии") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
                AdventureTextField(address, { address = it }, label = "Адрес сервера", singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = state.status != ConnectionStatus.CONNECTING)
                AdventureTextField(name, { name = it.take(24) }, label = "Ваше имя", singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = state.status != ConnectionStatus.CONNECTING)
                if (creating) {
                    Text("Нажмите карточку, чтобы создать комнату. После этого можно выбрать героя и пригласить друзей.", color = AdventureMuted)
                    ScenarioPicker(null, enabled = name.isNotBlank() && address.isNotBlank() && state.status != ConnectionStatus.CONNECTING,
                        actionLabel = "Создать комнату", unavailableHint = "Укажите имя и адрес сервера") { id ->
                        pendingScenario = id; client.connect(address, name)
                    }
                } else {
                    AdventureTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, label = "Код комнаты", singleLine = true, modifier = Modifier.fillMaxWidth())
                    AdventureButton(onClick = { client.connect(address, name, code) }, enabled = name.isNotBlank() && address.isNotBlank() && code.length == 6 && state.status != ConnectionStatus.CONNECTING,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Присоединиться") }
                    Text("Попросите создателя комнаты прислать адрес сервера и код приглашения.", color = AdventureMuted)
                }
                if (state.status == ConnectionStatus.CONNECTING) {
                    if (LocalReducedMotion.current) {
                        AdventureDivider()
                        Text("Подключаемся к серверу…", color = AdventureMuted)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(shape = MaterialTheme.shapes.small, onClick = client::leave) { Text("Отменить подключение") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        } else {
            if (room.adventure == null) Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Подготовка к приключению", style = MaterialTheme.typography.headlineMedium)
                Text("${room.members.size} из 4 игроков · Вы: ${room.members.firstOrNull { it.player.id == state.playerId }?.player?.name.orEmpty()}", color = AdventureMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmLeave = true }) { Text("Выйти") }
                    GameSettingsButton()
                    if (!connected) TextButton(shape = MaterialTheme.shapes.small, onClick = client::reconnect, enabled = state.status != ConnectionStatus.CONNECTING) {
                        Text(if (state.status == ConnectionStatus.CONNECTING) "Подключение…" else "Подключиться снова")
                    }
                    if (state.busy) Text("Ожидание сервера…", modifier = Modifier.padding(12.dp))
                }
                if (!connected) Text("Связь прервана. Подключитесь снова, чтобы продолжить.", color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                room.gameMasterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            val game = room.adventure
            if (game == null) {
                Column(Modifier.weight(1f).widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AdventureSectionTitle("Ожидаем друзей")
                    AdventurePanel(Modifier.fillMaxWidth(), emphasized = true) {
                        Text("ПРИГЛАШЕНИЕ В ОТРЯД", color = AdventureGold, style = MaterialTheme.typography.labelSmall)
                        Text(room.code, style = MaterialTheme.typography.headlineLarge)
                        Text("Передайте друзьям код и адрес сервера. Они смогут войти через «Присоединиться к игре».")
                        AdventureButton(secondary = true, onClick = { clipboard.setText(AnnotatedString("Истории у костра\nСервер: $address\nКод комнаты: ${room.code}")); copied = true }) { Text(if (copied) "Приглашение скопировано" else "Скопировать приглашение") }
                    }
                    ScenarioPicker(room.scenarioId, enabled = host && available, onlySelected = !choosingStory) { id ->
                        choosingStory = false
                        client.command(NetworkAction.SELECT_SCENARIO, scenarioId = id)
                    }
                    if (host) TextButton(shape = MaterialTheme.shapes.small, onClick = { choosingStory = !choosingStory }, enabled = available) {
                        Text(if (choosingStory) "Оставить выбранную историю" else "Сменить историю")
                    }
                    CharacterPicker(characters, room.members.firstOrNull { it.player.id == state.playerId }?.player?.hero?.profile,
                        enabled = available, unavailableNames = room.members.filter { it.player.id != state.playerId && it.characterConfirmed }.map { it.player.hero.name },
                        selectionConfirmed = room.members.firstOrNull { it.player.id == state.playerId }?.characterConfirmed == true) { profile ->
                        client.command(NetworkAction.SELECT_CHARACTER, character = profile)
                    }
                    AdventureSectionTitle("Ваш отряд")
                    room.members.forEach { member ->
                        AdventurePanel(Modifier.fillMaxWidth(), emphasized = member.player.id == state.playerId) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                member.player.hero.profile?.let { CharacterPortrait(it, Modifier.size(56.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text("${member.player.name} · ${member.player.hero.name}", style = MaterialTheme.typography.titleMedium)
                                    Text("${if (member.characterConfirmed) "Герой выбран" else "Выбирает героя"} · ${if (member.online) "в сети" else "отключён"}${if (member.player.id == room.hostPlayerId) " · создатель комнаты" else ""}", color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (host) room.members.filter { !it.online }.forEach { member ->
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { client.command(NetworkAction.REMOVE_DISCONNECTED, targetPlayerId = member.player.id) }, enabled = available) {
                            Text("Убрать из лобби: " + member.player.name)
                        }
                    }
                    if (host) AdventureButton(onClick = { client.command(NetworkAction.START) },
                        enabled = available && pendingScenario == null && room.members.size >= 2 && room.members.all { it.online && it.characterConfirmed },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Начать приключение") }
                    else Text("Ведущий комнаты начнёт приключение, когда все соберутся.")
                    if (room.members.size < 2) Text("Для начала нужен хотя бы один друг.", color = AdventureMuted)
                    else if (room.members.any { !it.online }) Text("Дождитесь подключения всех участников или уберите отключившихся из группы.", color = AdventureMuted)
                    else if (room.members.any { !it.characterConfirmed }) Text("Каждый участник должен подтвердить выбор героя.", color = AdventureMuted)
                }
            } else if (room.lifecycle != RoomLifecycle.CLOSED) {
                val speechGeneration = state.connectionGeneration
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AdventureScreen(
                        state = game,
                        speechBaseUrl = speechServerOrigin(client.serverAddress.ifBlank { address }),
                        serverTimeOffsetMillis = state.serverTimeOffsetMillis,
                        networkPresentation = room.presentation,
                        connectionGeneration = speechGeneration,
                        networkConnected = connected,
                        presentationPaused = paused,
                        pausedAtMillis = room.pausedAtMillis,
                        onSpeechAck = { acknowledgement -> client.speechAck(acknowledgement, speechGeneration) },
                        awaitingNarration = room.pendingNarration != null,
                        onFinished = { client.leave(); onBack() },
                        onSubmit = { _, turn, text -> client.command(NetworkAction.SUBMIT, turnId = turn, text = text) },
                        onRoll = { _, check -> client.command(NetworkAction.ROLL, checkId = check) },
                        onRollWithTalent = { _, check -> client.command(NetworkAction.ROLL, checkId = check, useRacialTalent = true) },
                        onCancelCheck = { _, check -> client.command(NetworkAction.CANCEL, checkId = check) },
                        onEndTurn = { _, turn -> client.command(NetworkAction.PASS, turnId = turn) },
                        onRestart = { client.command(NetworkAction.RESTART, sessionId = game.sessionId) },
                        controllingPlayerId = state.playerId,
                        commandsEnabled = available && room.pendingNarration == null,
                        restartEnabled = available && host,
                        aiNarrator = room.gameMasterEnabled,
                        onCombat = { action, target -> client.command(NetworkAction.COMBAT, turnId = game.turnId, combatAction = action, combatTarget = target) },
                        onExit = { confirmLeave = true },
                        statusText = state.error ?: room.gameMasterError ?: if (!connected) "Связь прервана. Подключитесь снова." else
                            room.pendingNarration?.takeIf { it.kind != NarrationKind.REACTION }?.let { "Собеседник отвечает…" },
                        onCancelNarration = room.pendingNarration?.takeIf { it.playerId == state.playerId && available && !paused }?.let { pending ->
                            { client.command(NetworkAction.CANCEL_NARRATION, turnId = pending.turnId, narrationId = pending.id) }
                        },
                        onSkipDisconnected = null,
                        onReconnect = if (!connected) client::reconnect else null,
                    )
                }
            }
        }
    }
    }
    if (room?.lifecycle == RoomLifecycle.CLOSED) {
        val leaver = sessionUi.closedByName ?: "Участник"
        Box(Modifier.fillMaxSize().background(Color(0xF5091118)).clickable(onClick = {}), contentAlignment = Alignment.Center) {
            AdventurePanel(Modifier.widthIn(max = 580.dp).padding(24.dp)) {
                Text("Партия завершена", style = MaterialTheme.typography.headlineMedium)
                Text("$leaver подтвердил выход. Приключение завершено для всей группы.")
                AdventureButton(onClick = { client.leave(); onBack() }) { Text("В главное меню") }
            }
        }
    } else if (paused && room != null) {
        val waiting = sessionUi.waitingNames
        Box(Modifier.fillMaxSize().background(Color(0xDF091118)).clickable(onClick = {}), contentAlignment = Alignment.Center) {
            AdventurePanel(Modifier.widthIn(max = 620.dp).padding(24.dp)) {
                Text("Партия на паузе", style = MaterialTheme.typography.headlineMedium)
                Text(if (waiting.isEmpty()) "Восстанавливаем связь с группой…" else "Ждём возвращения: ${waiting.joinToString()}.")
                Text("Текущий момент сохранён на сервере. Игра продолжится, когда все вернутся.", color = AdventureMuted)
                if (!connected) AdventureButton(onClick = client::reconnect, enabled = state.status != ConnectionStatus.CONNECTING) { Text("Подключиться снова") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameSettingsButton()
                    AdventureButton(secondary = true, onClick = { confirmLeave = true }) { Text("Завершить партию…") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmLeave) AdventureDialog(
        onDismissRequest = { confirmLeave = false },
        title = "Выйти из комнаты?",
        text = {
            Text(leaveUi.explanation)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
        confirmButton = { AdventureButton(secondary = true, onClick = {
            if (leaveUi.requiresAcknowledgement) { awaitingLeaveAcknowledgement = true; client.requestLeave() }
            else { confirmLeave = false; client.leave(); onBack() }
        }, enabled = !state.leavePending) { Text(if (state.leavePending) "Ожидаем сервер…" else "Выйти") } },
        dismissButton = { AdventureButton(onClick = { confirmLeave = false }) { Text("Остаться") } },
    )
}
