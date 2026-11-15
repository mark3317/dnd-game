package ru.markn.dnd.adventure.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.markn.dnd.adventure.*

@Composable
fun AdventureScreen(
    state: AdventureState,
    onSubmit: (String, TurnId, String) -> Unit,
    onRoll: (String, CheckId) -> Unit,
    onCancelCheck: (String, CheckId) -> Unit,
    onEndTurn: (String, TurnId) -> Unit,
    onRestart: () -> Unit,
    controllingPlayerId: String? = null,
    commandsEnabled: Boolean = true,
    restartEnabled: Boolean = true,
    aiNarrator: Boolean = false,
    onRollWithTalent: ((String, CheckId) -> Unit)? = null,
    onCombat: ((ru.markn.dnd.combat.DemoAction?, ru.markn.dnd.combat.Tile?) -> Unit)? = null,
    statusText: String? = null, onExit: (() -> Unit)? = null,
    onCancelNarration: (() -> Unit)? = null, onSkipDisconnected: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
    serverTimeOffsetMillis: Long = 0,
    awaitingNarration: Boolean = false,
    onFinished: (() -> Unit)? = null,
    speechBaseUrl: String = "http://localhost:8080",
) {
    var now by remember { mutableLongStateOf(storyNowMillis() + serverTimeOffsetMillis) }
    LaunchedEffect(serverTimeOffsetMillis) {
        while (true) { now = storyNowMillis() + serverTimeOffsetMillis; kotlinx.coroutines.delay(16) }
    }
    val playback = state.playback
    val voice = rememberStorySpeech(state, now, speechBaseUrl)
    val showOpening = now < playback.openingEndsAt
    val showEnding = playback.endingAt > 0 && now >= playback.endingAt
    val endingFinished = showEnding && now >= playback.busyUntil && !awaitingNarration && !voice.busy
    val finishCallback by rememberUpdatedState(onFinished)
    LaunchedEffect(state.sessionId, endingFinished) { if (endingFinished) finishCallback?.invoke() }
    // Buffered captions advance with playback, never with a response that is still synthesizing.
    val speaking = if (voice.available) voice.current ?: voice.caption
        else playback.speeches.firstOrNull { now in it.startsAt until it.endsAt }
            ?: playback.speeches.lastOrNull { it.startsAt <= now }
    val busy = now < playback.busyUntil || voice.busy
    val combatEntrance = remember(state.sessionId) { CombatEntrance() }
    LaunchedEffect(state.combat != null, busy, awaitingNarration) {
        combatEntrance.update(state.combat != null, busy || awaitingNarration)
    }
    val actorState = if (busy && playback.actorId != null && state.players.any { it.id == playback.actorId })
        state.copy(turnOrder = state.turnOrder.copy(currentPlayerId = playback.actorId)) else state
    // Keep the authored location, avatars and captions while the encounter introduction plays.
    val displayedState = if (combatEntrance.visible) actorState else actorState.copy(combat = null)
    val music = LocalStoryMusic.current
    val cue = when {
        state.ending != null -> if (state.outcome?.success == true) MusicCue.VICTORY else MusicCue.DEFEAT
        combatEntrance.visible -> MusicCue.COMBAT
        showOpening -> MusicCue.OPENING
        else -> MusicCue.EXPLORATION
    }
    SideEffect { music?.cue = cue }
    DisposableEffect(music) { onDispose { music?.cue = MusicCue.MENU } }
    val rolls = state.presentedRolls()
    val queue = remember(state.sessionId) { RollPresentationQueue(rolls) }
    val presentationPending = queue.pending.isNotEmpty() || queue.hasUnseen(rolls)
    val initiativePending = queue.pending.any { it.combat?.kind == ru.markn.dnd.combat.CombatEventKind.INITIATIVE } ||
        queue.hasUnseen(rolls.filter { it.combat?.kind == ru.markn.dnd.combat.CombatEventKind.INITIATIVE })
    LaunchedEffect(rolls) { queue.receive(rolls) }
    CompositionLocalProvider(LocalStorySpeech provides voice) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(displayedState, modifier = Modifier.fillMaxSize(), contentKey = { it.combat != null },
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) }, label = "adventure combat transition") { scene ->
          AdventureContent(scene, onSubmit, onRoll, onCancelCheck, onEndTurn, onRestart,
            controllingPlayerId, commandsEnabled && !presentationPending && !busy && !awaitingNarration &&
                (state.combat == null || combatEntrance.visible), restartEnabled,
            aiNarrator, onRollWithTalent, onCombat, statusText, onExit, onCancelNarration,
            onSkipDisconnected, onReconnect, speaking, now, !initiativePending, voice.available)
        }
        RollPresentationHost(queue, controllingPlayerId,
            combatPresentationsEnabled = state.combat == null || combatEntrance.visible)
        if (combatEntrance.visible && busy && speaking != null) StorySubtitle(
            speaking.entry.text, state.speakerName(speaking.entry), now - speaking.startsAt,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        AnimatedVisibility(showOpening || showEnding, enter = fadeIn(tween(600)), exit = fadeOut(tween(400))) {
            StoryCinema(state, ending = state.ending != null, now = now,
                speech = voice.current ?: speaking?.takeIf { state.ending != null && it.startsAt >= playback.endingEndsAt })
        }
    }
    }
}

@Composable
private fun AdventureContent(
    state: AdventureState,
    onSubmit: (String, TurnId, String) -> Unit,
    onRoll: (String, CheckId) -> Unit,
    onCancelCheck: (String, CheckId) -> Unit,
    onEndTurn: (String, TurnId) -> Unit,
    onRestart: () -> Unit,
    controllingPlayerId: String?, commandsEnabled: Boolean, restartEnabled: Boolean,
    aiNarrator: Boolean, onRollWithTalent: ((String, CheckId) -> Unit)?,
    onCombat: ((ru.markn.dnd.combat.DemoAction?, ru.markn.dnd.combat.Tile?) -> Unit)?,
    statusText: String?, onExit: (() -> Unit)?, onCancelNarration: (() -> Unit)?,
    onSkipDisconnected: (() -> Unit)?, onReconnect: (() -> Unit)?,
    speech: TimedSpeech?, now: Long, initiativeVisible: Boolean, bufferedSpeech: Boolean,
) {
    val combat = state.combat
    if (combat != null) {
        ru.markn.dnd.combat.CombatPrototypeScreen(onBack = { onExit?.invoke() }, battleState = combat, onReconnect = onReconnect,
            statusText = statusText,
            onCommand = onCombat, initiativeVisible = initiativeVisible, commandsEnabled = commandsEnabled &&
                (controllingPlayerId == null || controllingPlayerId == combat.activeId))
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf("Меню") }
    var selectedHero by remember(state.sessionId) { mutableStateOf(controllingPlayerId ?: state.activePlayer.id) }
    var showRestart by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var actionDraft by remember(state.turnId, state.journal.lastOrNull()?.id) { mutableStateOf("") }
    val canControl = commandsEnabled && (controllingPlayerId == null || controllingPlayerId == state.activePlayer.id)
    fun open(name: String) { panel = name; scope.launch { drawer.open() } }
    fun close() { scope.launch { drawer.close() } }
    val pendingCheck = state.pendingCheck
    val subtitle = speech?.entry ?: state.lastSpokenEntry?.takeUnless { bufferedSpeech }?.let { entry ->
        entry.copy(text = subtitleSegments(entry.text).lastOrNull().orEmpty())
    }
    LaunchedEffect(state.pendingCheck?.id, canControl) {
        if (state.pendingCheck != null && canControl) { panel = "Проверка"; drawer.open() }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 410.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(panel, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(shape = MaterialTheme.shapes.small, onClick = { close() }) { Text("Закрыть") }
            }
            HorizontalDivider()
            when (panel) {
                "Текст истории" -> Journal(state, Modifier.weight(1f).fillMaxWidth())
                "Проверка" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    val check = state.pendingCheck
                    if (check != null && canControl) CheckPanel(check, state,
                        onRoll = { close(); onRoll(state.activePlayer.id, it) },
                        onCancel = { close(); onCancelCheck(state.activePlayer.id, it) },
                        onRollWithTalent = onRollWithTalent?.let { callback -> { id -> close(); callback(state.activePlayer.id, id) } })
                    else Text("Сейчас нет доступной вам проверки.", Modifier.padding(20.dp))
                }
                "Персонажи" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.players.forEach { player ->
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { selectedHero = player.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(player.hero.name + if (player.id == state.activePlayer.id) " · сейчас ходит" else "")
                        }
                    }
                    val player = state.players.firstOrNull { it.id == selectedHero } ?: state.activePlayer
                    Text(state.scenario.sites.getValue(player.location).title, color = AdventureMuted)
                    HeroPanel(player.hero, state.scenario.items)
                }
                "Действия" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.suggestions.forEach { action ->
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { close(); onSubmit(state.activePlayer.id, state.turnId, action.text) },
                            enabled = canControl && state.canAct, modifier = Modifier.fillMaxWidth()) { Text(action.text) }
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.scenario.title, style = MaterialTheme.typography.titleMedium)
                    Text(state.scenario.goal)
                    GameSettingsButton()
                    Text("${state.scenario.pressure}: ${state.checksRemaining}", color = AdventureMuted)
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { showRules = true }) { Text("Правила и управление") }
                    if (onReconnect != null) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onReconnect) { Text("Подключиться снова") }
                    if (onSkipDisconnected != null) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { close(); onSkipDisconnected() }) { Text("Пропустить отключившегося игрока") }
                    if (restartEnabled) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { showRestart = true }) { Text("Начать заново") }
                    if (onExit != null) TextButton(shape = MaterialTheme.shapes.small, onClick = { close(); onExit() }) { Text("Выйти из истории") }
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.scenarioId == ArchiveDemo.ID || state.scenarioId == FloodedArchive.ID)
                    ArchiveScene(state, modifier = Modifier.fillMaxSize(), spokenEntry = subtitle)
                else LighthouseScene(state, Modifier.fillMaxSize())
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = .82f)) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.scenario.sites.getValue(state.location).title, Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, maxLines = 2)
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { open("Персонажи") }) { Text("Герои") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { open("Текст истории") }) { Text("Текст") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { open("Меню") }) { Text("Меню") }
                    }
                }
                if (subtitle != null) StorySubtitle(subtitle.text, state.speakerName(subtitle),
                    if (speech == null) Long.MAX_VALUE / 2 else now - speech.startsAt,
                    Modifier.align(Alignment.BottomCenter))
            }
            if (onReconnect != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Связь прервана", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onReconnect) { Text("Подключиться снова") }
                }
            }
            if (statusText != null && onReconnect == null) Text(statusText, Modifier.padding(horizontal = 12.dp),
                color = AdventureMuted, style = MaterialTheme.typography.labelSmall)
            val notice = state.journal.lastOrNull()?.takeIf { it.kind == EntryKind.SYSTEM && pendingCheck == null }
            if (notice != null) Text(notice.text, Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall, color = AdventureMuted)
            when {
                state.ending != null -> Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.outcome!!.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { open("Текст истории") }) { Text("Итог") }
                }
                !canControl -> if (now >= state.playback.busyUntil) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (statusText == null && !commandsEnabled) "Ожидаем ответ…" else "Ход: ${state.activePlayer.hero.name}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    if (onCancelNarration != null) TextButton(shape = MaterialTheme.shapes.small, onClick = onCancelNarration) { Text("Отменить") }
                }
                state.pendingCheck != null -> Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pendingCheck!!.description, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    Button(shape = MaterialTheme.shapes.small, onClick = { open("Проверка") }) { Text("Проверка") }
                }
                else -> Unit
            }
            AnimatedVisibility(canControl && state.canAct,
                enter = fadeIn(tween(250)) + expandVertically(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                exit = fadeOut(tween(180)) + shrinkVertically(tween(250)) + slideOutVertically(tween(250)) { it / 2 }) {
                ActionComposer(state,
                    onSubmit = { onSubmit(state.activePlayer.id, state.turnId, it) },
                    onEndTurn = { onEndTurn(state.activePlayer.id, state.turnId) },
                    draft = actionDraft, onDraftChange = { actionDraft = it },
                    onShowActions = { open("Действия") })
            }
        }
    }
    if (showRestart) AlertDialog(onDismissRequest = { showRestart = false }, title = { Text("Начать историю заново?") },
        text = { Text("Текущий прогресс приключения будет сброшен.") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showRestart = false; close(); onRestart() }) { Text("Начать заново") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showRestart = false }) { Text("Продолжить") } })
    if (showRules) AlertDialog(onDismissRequest = { showRules = false }, title = { Text("Правила и управление") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Опишите действие или реплику героя. Готовые действия доступны кнопкой «Действия». Персонажи и весь текст истории открываются отдельно поверх сцены.")
            Text("Рискованное действие: d20 + модификатор + подходящая специализация (+1). Расовый талант: ещё +2 один раз за историю. Итог не ниже сложности — успех. До броска показаны последствия и цена; отмена сохраняет попытку.")
            Text("Бросок расходует одну общую проверку, разговоры и осмотр — нет. У каждого героя свои позиция и предметы. В бою выбирайте движение, действие и цель. Повязка лечит на 4; поднять героя можно один раз за бой. После победы здоровье не ниже 4, повязки не пополняются.")
            Text("Игровые правила находятся здесь; рассказчик описывает события, а NPC говорят от своего лица. Озвучка пока недоступна; субтитры показывают последнюю реплику.")
        } }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showRules = false }) { Text("Понятно") } })
}
@Composable
private fun HeroPanel(hero: Hero, itemNames: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        hero.profile?.let { CharacterPortrait(it, Modifier.size(120.dp)) }
        Column {
            Text("ВАШ ПЕРСОНАЖ", color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text(hero.name, fontFamily = MaterialTheme.typography.headlineLarge.fontFamily, fontSize = 30.sp)
            Text(hero.archetype, color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
            hero.profile?.let { profile ->
                Text("${profile.race.title} · ${profile.background.title}", style = MaterialTheme.typography.bodySmall)
                Text(profile.description, style = MaterialTheme.typography.bodySmall)
                Text("Класс +1: ${profile.characterClass.domains.joinToString { it.title }}. Предыстория +1: ${profile.background.domain.title}. Не складываются.", style = MaterialTheme.typography.bodySmall)
                Text("${profile.race.talent}: ${if (hero.racialTalentUsed) "использован" else "+2 один раз — " + profile.race.domains.joinToString { it.title }}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Здоровье", style = MaterialTheme.typography.bodySmall)
            Text("${hero.health} / ${hero.maxHealth}", color = AdventureGreen, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { hero.health.toFloat() / hero.maxHealth },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = AdventureGreen,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Ability.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ability ->
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ability.abbreviation + (hero.profile?.scores?.get(ability)?.let { " $it" } ?: ""), color = AdventureMuted, fontSize = 10.sp)
                        Text(signed(hero.modifier(ability)), fontSize = 20.sp, color = AdventureGold)
                    }
                }
            }
        }
        Text("СНАРЯЖЕНИЕ", color = AdventureMuted, fontSize = 10.sp, letterSpacing = 1.sp)
        hero.inventory.forEach { Text("— ${itemNames.getValue(it)}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Journal(state: AdventureState, modifier: Modifier) {
    val scroll = rememberLazyListState()
    LaunchedEffect(state.sessionId, state.journal.size) {
        if (state.journal.isNotEmpty()) scroll.animateScrollToItem(state.journal.lastIndex)
    }
    LazyColumn(
        modifier = modifier,
        state = scroll,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(state.journal, key = { "${state.sessionId}:${it.id}" }) { entry ->
            JournalItem(entry, state.speakerName(entry))
        }
    }
}

@Composable
private fun JournalItem(entry: JournalEntry, speaker: String) {
    if (entry.kind == EntryKind.TURN) {
        Text(entry.text, color = AdventureMuted, style = MaterialTheme.typography.labelMedium)
        return
    }
    val isPlayer = entry.kind == EntryKind.PLAYER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isPlayer) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp)
                .then(
                    if (isPlayer) Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp)
                    else Modifier.padding(vertical = 2.dp),
                ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                when (entry.kind) {
                    EntryKind.PLAYER -> entry.actorName.orEmpty().uppercase()
                    EntryKind.NARRATOR -> "РАССКАЗЧИК"
                    EntryKind.NPC -> speaker.uppercase()
                    EntryKind.SYSTEM -> "ИГРОВОЕ СОБЫТИЕ"
                    EntryKind.CHECK -> "БРОСОК КУБИКА · ${entry.actorName.orEmpty()}"
                    EntryKind.TURN -> "ОЧЕРЕДЬ ХОДОВ"
                },
                color = if (isPlayer) AdventureGreen else AdventureGold,
                fontSize = 10.sp, letterSpacing = 1.sp,
            )
            val roll = entry.roll
            if (roll != null) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(roll.die.toString(), color = AdventureGold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.text,
                            color = if (roll.succeeded) AdventureGreen else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${roll.ability.title}: ${roll.die} ${signed(roll.modifier)} = ${roll.total} · сложность ${roll.difficulty}",
                            color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
                        )
                        roll.bonuses?.let { bonuses -> Text(
                            "Характеристика ${signed(bonuses.ability)} · специализация +${bonuses.expertise} · талант +${bonuses.racial}",
                            color = AdventureMuted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } else {
                Text(entry.text, lineHeight = 25.sp, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ActionComposer(
    state: AdventureState, onSubmit: (String) -> Unit, onEndTurn: () -> Unit,
    draft: String, onDraftChange: (String) -> Unit, onShowActions: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = draft.isNotBlank() && draft.trim().length <= AdventureEngine.MAX_ACTION_LENGTH
    fun send() { if (valid) { onSubmit(draft); onDraftChange(""); keyboard?.hide() } }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= AdventureEngine.MAX_ACTION_LENGTH) onDraftChange(it) },
                modifier = Modifier.weight(1f), placeholder = { Text("Реплика или действие героя") }, maxLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }), shape = RoundedCornerShape(12.dp))
            Button(shape = MaterialTheme.shapes.small, onClick = { send() }, enabled = valid) { Text("Отправить") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.activePlayer.hero.name} · ваш ход", Modifier.weight(1f), color = AdventureMuted, style = MaterialTheme.typography.labelSmall)
            TextButton(shape = MaterialTheme.shapes.small, onClick = onShowActions) { Text("Действия") }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { keyboard?.hide(); onEndTurn() }) { Text("Передать ход") }
        }
    }
}
@Composable
private fun CheckPanel(
    check: PendingCheck, state: AdventureState, onRoll: (CheckId) -> Unit, onCancel: (CheckId) -> Unit,
    onRollWithTalent: ((CheckId) -> Unit)? = null,
) {
    val proposed = requireNotNull(check.plan?.check)
    val bonuses = CharacterRules.bonuses(state.activePlayer.hero, proposed)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("НУЖНА ПРОВЕРКА", color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(check.description)
        if (proposed.domains.isNotEmpty()) Text("Области: " + proposed.domains.joinToString { it.title }, style = MaterialTheme.typography.bodySmall)
        Text(
            "${check.ability.title} · d20 ${signed(bonuses.ability)} · специализация +${bonuses.expertise} · итого ${signed(bonuses.total)} · сложность ${check.difficulty}",
            color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
        )
        check.plan?.let { plan ->
            Text("При успехе: " + plan.onSuccess.joinToString(" ") { SceneRules.describe(state, it) },
                style = MaterialTheme.typography.bodySmall)
            Text("При провале: " + if (plan.onFailure.isEmpty()) "сцена не изменится." else
                plan.onFailure.joinToString(" ") { SceneRules.describe(state, it) },
                style = MaterialTheme.typography.bodySmall)
            Text("Бросок расходует одну общую проверку при любом исходе. До броска можно отменить попытку.",
                color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(shape = MaterialTheme.shapes.small, onClick = { onRoll(check.id) }) { Text("Бросить d20") }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { onCancel(check.id) }) { Text("Выбрать другое действие") }
        }
        if (CharacterRules.canUseTalent(state, proposed) && onRollWithTalent != null) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onRollWithTalent(check.id) }) {
                Text("${state.activePlayer.hero.profile!!.race.talent}: бросить с +2 (итого ${signed(bonuses.total + 2)})")
            }
        }
    }
}

private fun signed(value: Int): String = if (value >= 0) "+$value" else "−${-value}"

@Preview
@Composable
private fun AdventurePreview() {
    AdventureTheme {
        AdventureScreen(AdventureEngine().newGame(), { _, _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}
