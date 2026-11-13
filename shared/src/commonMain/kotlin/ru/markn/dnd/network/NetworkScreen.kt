package ru.markn.dnd.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.markn.dnd.adventure.ui.AdventureScreen
import ru.markn.dnd.adventure.ui.ScenarioPicker

@Composable
fun NetworkScreen(client: NetworkGameClient, state: NetworkState, onLocal: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("ws://localhost:8080") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val room = state.room
    val connected = state.status == ConnectionStatus.CONNECTED
    val host = room != null && room.hostPlayerId == state.playerId
    val available = connected && !state.busy
    var confirmLeave by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (room == null) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Истории у костра", style = MaterialTheme.typography.headlineMedium)
                Text("Приключение с друзьями", style = MaterialTheme.typography.titleLarge)
                Text("Подключитесь к одному серверу. Создайте комнату и передайте друзьям её код.")
                OutlinedTextField(address, { address = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = state.status != ConnectionStatus.CONNECTING)
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Ваше имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.uppercase().take(6) }, label = { Text("Код комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { client.connect(address, name) }, enabled = name.isNotBlank() && state.status != ConnectionStatus.CONNECTING) { Text("Создать комнату") }
                OutlinedButton(onClick = { client.connect(address, name, code) }, enabled = name.isNotBlank() && code.length == 6 && state.status != ConnectionStatus.CONNECTING) { Text("Присоединиться") }
                if (state.status == ConnectionStatus.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = client::leave) { Text("Отменить подключение") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                TextButton(onClick = onLocal) { Text("Играть локально на одном устройстве") }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Комната ${room.code} · Вы: ${room.members.firstOrNull { it.player.id == state.playerId }?.player?.name.orEmpty()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmLeave = true }) { Text("Выйти") }
                    if (!connected) TextButton(onClick = client::reconnect, enabled = state.status != ConnectionStatus.CONNECTING) {
                        Text(if (state.status == ConnectionStatus.CONNECTING) "Подключение…" else "Подключиться снова")
                    }
                    if (state.busy) Text("Ожидание сервера…", modifier = Modifier.padding(12.dp))
                }
                if (!connected) Text("Связь прервана. Подключитесь снова, чтобы продолжить.", color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(if (room.gameMasterEnabled) "Ведущий: Claude · действия можно описывать своими словами" else "Ведущий: сценарный прототип")
                room.gameMasterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                room.pendingNarration?.let { pending ->
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Ведущий думает…")
                    if (pending.playerId == state.playerId) TextButton(
                        enabled = available,
                        onClick = { client.command(NetworkAction.CANCEL_NARRATION, turnId = pending.turnId, narrationId = pending.id) },
                    ) { Text("Отменить запрос") }
                }
                val game = room.adventure
                if (game != null && game.ending == null && host &&
                    room.members.none { it.player.id == game.activePlayer.id && it.online }) {
                    OutlinedButton(onClick = { client.command(NetworkAction.SKIP_DISCONNECTED, turnId = game.turnId) }, enabled = available) {
                        Text("Пропустить ход отключившегося игрока")
                    }
                }
            }
            val game = room.adventure
            if (game == null) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Ожидаем друзей", style = MaterialTheme.typography.headlineSmall)
                    Text("Код для приглашения: ${room.code}")
                    ScenarioPicker(room.scenarioId, enabled = host && available) { id ->
                        client.command(NetworkAction.SELECT_SCENARIO, scenarioId = id)
                    }
                    room.members.forEach {
                        Text("${it.player.name} · ${it.player.hero.name} · ${if (it.online) "в сети" else "отключён"}${if (it.player.id == room.hostPlayerId) " · ведущий комнаты" else ""}")
                    }
                    if (host) room.members.filter { !it.online }.forEach { member ->
                        TextButton(onClick = { client.command(NetworkAction.REMOVE_DISCONNECTED, targetPlayerId = member.player.id) }, enabled = available) {
                            Text("Убрать из лобби: " + member.player.name)
                        }
                    }
                    Text("В этом пробном сюжете участвуют 2–3 игрока. Персонажи назначаются по порядку входа.")
                    if (host) Button(
                        onClick = { client.command(NetworkAction.START) },
                        enabled = available && room.members.size >= 2 && room.members.all { it.online },
                    ) { Text("Начать приключение") }
                    else Text("Ведущий комнаты начнёт приключение, когда все соберутся.")
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AdventureScreen(
                        state = game,
                        onSubmit = { _, turn, text -> client.command(NetworkAction.SUBMIT, turnId = turn, text = text) },
                        onRoll = { _, check -> client.command(NetworkAction.ROLL, checkId = check) },
                        onCancelCheck = { _, check -> client.command(NetworkAction.CANCEL, checkId = check) },
                        onEndTurn = { _, turn -> client.command(NetworkAction.PASS, turnId = turn) },
                        onRestart = { client.command(NetworkAction.RESTART, sessionId = game.sessionId) },
                        controllingPlayerId = state.playerId,
                        commandsEnabled = available && room.pendingNarration == null,
                        restartEnabled = available && host,
                        aiNarrator = room.gameMasterEnabled,
                    )
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Выйти из комнаты?") },
        text = { Text("После выхода вернуться к своему персонажу с этого экрана не получится. При обрыве связи используйте «Подключиться снова».") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; client.leave() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Остаться") } },
    )
}
