package ru.markn.dnd

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.network.*
import ru.markn.dnd.combat.CombatPrototypeScreen

@Composable
fun App(onExit: (() -> Unit)? = null) {
    val music = remember { StoryMusic() }
    val preferences = remember { GamePreferences() }
    val network = viewModel { NetworkViewModel() }
    val networkState by network.client.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var localScenarioChosen by rememberSaveable { mutableStateOf(false) }
    var confirmLocalExit by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val leaveUi = networkState.leavePresentation()
    var exitAfterLeave by remember { mutableStateOf(false) }
    LaunchedEffect(networkState.leaveAcknowledged, exitAfterLeave) {
        if (exitAfterLeave && networkState.leaveAcknowledged) onExit?.invoke()
    }
    var selectedHero by remember { mutableStateOf<CharacterProfile?>(null) }
    LaunchedEffect(page, localScenarioChosen, networkState.room?.adventure != null) {
        if (page != "combat" && !(page == "local" && localScenarioChosen) &&
            !(page in listOf("create", "join") && networkState.room?.adventure != null)) music.cue = MusicCue.MENU
    }
    CompositionLocalProvider(LocalStoryMusic provides music, LocalGameMotion provides preferences.motion,
        LocalReducedMotion provides preferences.reduceMotion, LocalGamePreferences provides preferences) {
        StoryMusicHost(music)
        AdventureTheme {
            Surface(Modifier.fillMaxSize()) {
                AnimatedContent(page, transitionSpec = {
                    fadeIn(tween(GameMotionProfile(preferences.reduceMotion).transitionMillis(280))) togetherWith
                        fadeOut(tween(GameMotionProfile(preferences.reduceMotion).transitionMillis(180)))
                }, label = "menu transition") { route ->
                    when (route) {
                        "home" -> MainMenu(onCreate = { page = "create" }, onJoin = { page = "join" },
                            onCharacter = { page = "character" }, onSettings = { page = "settings" },
                            onExit = onExit?.let { { confirmExit = true } },
                            onResume = if (networkState.canResumeSession) ({ page = "join"; network.client.reconnect() }) else null, networkError = networkState.error)
                        "create", "join" -> NetworkScreen(network.client, networkState, network.characters,
                            creating = route == "create", onBack = { page = "home" })
                        "character" -> GameBackdrop {
                            Column(Modifier.fillMaxHeight().widthIn(max = 1100.dp).fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter)
                                .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                MenuHeader("Персонаж", "Создайте героя и выберите его облик", onBack = { page = "home" })
                                CharacterPicker(network.characters, selectedHero) { selectedHero = it }
                            }
                        }
                        "settings" -> GameSettings(preferences, onBack = { page = "home" }, onLocal = { page = "local" }, onCombat = { page = "combat" })
                        "combat" -> CombatPrototypeScreen(onBack = { page = "settings" })
                        "local" -> {
                            val adventure = viewModel { AdventureViewModel() }
                            if (!localScenarioChosen) GameBackdrop {
                                Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    TextButton(shape = MaterialTheme.shapes.small, onClick = { page = "settings" }) { Text("‹  Назад") }
                                    LocalAdventureSetup(network.characters, adventure.party) { id, party ->
                                        adventure.selectParty(party); adventure.selectScenario(id); localScenarioChosen = true
                                    }
                                }
                            } else AdventureScreen(
                                speechBaseUrl = "http://localhost:8091",
                                state = adventure.state, onSubmit = adventure::submit, onRoll = adventure::roll,
                                onRollWithTalent = adventure::rollWithTalent, onCancelCheck = adventure::cancelCheck,
                                onEndTurn = adventure::endTurn, onRestart = adventure::restart, onCombat = adventure::combat,
                                onExit = { confirmLocalExit = true },
                                onFinished = { localScenarioChosen = false; page = "home" },
                            )
                        }
                    }
                }
                if (confirmLocalExit) AdventureDialog(onDismissRequest = { confirmLocalExit = false },
                    title = "Вернуться в меню?", text = { Text("При выборе новой истории текущая локальная партия будет заменена.") },
                    confirmButton = { AdventureButton(secondary = true, onClick = { confirmLocalExit = false; localScenarioChosen = false; page = "home" }) { Text("В меню") } },
                    dismissButton = { AdventureButton(onClick = { confirmLocalExit = false }) { Text("Продолжить") } })
                if (confirmExit) AdventureDialog(onDismissRequest = { confirmExit = false },
                    title = "Покинуть костёр?", text = {
                        Text(leaveUi.explanation)
                        if (networkState.room?.lifecycle in listOf(RoomLifecycle.ACTIVE, RoomLifecycle.PAUSED))
                            Text("Закрытие окна без подтверждения оставляет группу ждать вашего возвращения.")
                        networkState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    confirmButton = { AdventureButton(secondary = true, onClick = {
                        if (leaveUi.requiresAcknowledgement) {
                            exitAfterLeave = true; network.client.requestLeave()
                        } else onExit?.invoke()
                    }, enabled = !networkState.leavePending) { Text(if (networkState.leavePending) "Ожидаем сервер…" else "Выйти") } },
                    dismissButton = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (networkState.canResumeSession && networkState.status != ConnectionStatus.CONNECTED)
                            AdventureButton(secondary = true, onClick = network.client::reconnect, enabled = networkState.status != ConnectionStatus.CONNECTING) { Text("Восстановить связь") }
                        AdventureButton(onClick = { confirmExit = false; exitAfterLeave = false }) { Text("Остаться") }
                    } })
            }
        }
    }
}
