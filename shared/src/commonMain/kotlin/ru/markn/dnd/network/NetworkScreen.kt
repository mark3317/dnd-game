package ru.markn.dnd.network

import androidx.compose.foundation.layout.*
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
    val available = connected && !state.busy
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
                    if (creating) "Выберите историю — и соберите свой отряд" else "У каждого приключения есть место для друга", onBack = { client.leave(); onBack() })
                OutlinedTextField(address, { address = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = state.status != ConnectionStatus.CONNECTING)
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Ваше имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = state.status != ConnectionStatus.CONNECTING)
                if (creating) {
                    Text("Нажмите карточку, чтобы создать комнату. После этого можно выбрать героя и пригласить друзей.", color = AdventureMuted)
                    ScenarioPicker(null, enabled = name.isNotBlank() && address.isNotBlank() && state.status != ConnectionStatus.CONNECTING,
                        actionLabel = "Создать комнату", unavailableHint = "Укажите имя и адрес сервера") { id ->
                        pendingScenario = id; client.connect(address, name)
                    }
                } else {
                    OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, label = { Text("Код комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(shape = MaterialTheme.shapes.small, onClick = { client.connect(address, name, code) }, enabled = name.isNotBlank() && address.isNotBlank() && code.length == 6 && state.status != ConnectionStatus.CONNECTING,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Присоединиться") }
                    Text("Попросите создателя комнаты прислать адрес сервера и код приглашения.", color = AdventureMuted)
                }
                if (state.status == ConnectionStatus.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(shape = MaterialTheme.shapes.small, onClick = client::leave) { Text("Отменить подключение") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    Text("Ожидаем друзей", style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f))) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ПРИГЛАШЕНИЕ В ОТРЯД", color = AdventureGold, style = MaterialTheme.typography.labelSmall)
                            Text(room.code, style = MaterialTheme.typography.headlineLarge)
                            Text("Передайте друзьям код и адрес сервера. Они смогут войти через «Присоединиться к игре».")
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { clipboard.setText(AnnotatedString("Истории у костра\nСервер: $address\nКод комнаты: ${room.code}")); copied = true }) { Text(if (copied) "Приглашение скопировано" else "Скопировать приглашение") }
                        }
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
                    Text("Ваш отряд", style = MaterialTheme.typography.titleLarge)
                    room.members.forEach { member ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (host) Button(shape = MaterialTheme.shapes.small, onClick = { client.command(NetworkAction.START) },
                        enabled = available && pendingScenario == null && room.members.size >= 2 && room.members.all { it.online && it.characterConfirmed },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Начать приключение") }
                    else Text("Ведущий комнаты начнёт приключение, когда все соберутся.")
                    if (room.members.size < 2) Text("Для начала нужен хотя бы один друг.", color = AdventureMuted)
                    else if (room.members.any { !it.online }) Text("Дождитесь подключения всех участников или уберите отключившихся из группы.", color = AdventureMuted)
                    else if (room.members.any { !it.characterConfirmed }) Text("Каждый участник должен подтвердить выбор героя.", color = AdventureMuted)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AdventureScreen(
                        state = game,
                        speechBaseUrl = speechServerOrigin(address),
                        serverTimeOffsetMillis = state.serverTimeOffsetMillis,
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
                        onCancelNarration = room.pendingNarration?.takeIf { it.playerId == state.playerId }?.let { pending ->
                            { client.command(NetworkAction.CANCEL_NARRATION, turnId = pending.turnId, narrationId = pending.id) }
                        },
                        onSkipDisconnected = if (host && room.members.none { it.player.id == game.activePlayer.id && it.online })
                            ({ client.command(NetworkAction.SKIP_DISCONNECTED, turnId = game.turnId) }) else null,
                        onReconnect = if (!connected) client::reconnect else null,
                    )
                }
            }
        }
    }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Выйти из комнаты?") },
        text = { Text("После выхода вернуться к своему персонажу с этого экрана не получится. При обрыве связи используйте «Подключиться снова».") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmLeave = false; client.leave(); onBack() }) { Text("Выйти") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmLeave = false }) { Text("Остаться") } },
    )
}
