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
    var selectedHero by remember { mutableStateOf<CharacterProfile?>(null) }
    LaunchedEffect(page, localScenarioChosen, networkState.room?.adventure != null) {
        if (page != "combat" && !(page == "local" && localScenarioChosen) &&
            !(page in listOf("create", "join") && networkState.room?.adventure != null)) music.cue = MusicCue.MENU
    }
    CompositionLocalProvider(LocalStoryMusic provides music, LocalGameMotion provides preferences.motion, LocalGamePreferences provides preferences) {
        StoryMusicHost(music)
        AdventureTheme {
            Surface(Modifier.fillMaxSize()) {
                AnimatedContent(page, transitionSpec = { fadeIn(tween(if (preferences.motion) 280 else 0)) togetherWith fadeOut(tween(if (preferences.motion) 180 else 0)) }, label = "menu transition") { route ->
                    when (route) {
                        "home" -> MainMenu(onCreate = { page = "create" }, onJoin = { page = "join" },
                            onCharacter = { page = "character" }, onSettings = { page = "settings" },
                            onExit = onExit?.let { { confirmExit = true } })
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
                if (confirmLocalExit) AlertDialog(onDismissRequest = { confirmLocalExit = false },
                    title = { Text("Вернуться в меню?") }, text = { Text("При выборе новой истории текущая локальная партия будет заменена.") },
                    confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmLocalExit = false; localScenarioChosen = false; page = "home" }) { Text("В меню") } },
                    dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmLocalExit = false }) { Text("Продолжить") } })
                if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
                    title = { Text("Покинуть костёр?") }, text = { Text("Выйти из игры на рабочий стол?") },
                    confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onExit?.invoke() }) { Text("Выйти") } },
                    dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmExit = false }) { Text("Остаться") } })
            }
        }
    }
}
