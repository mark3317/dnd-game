package ru.markn.dnd.adventure.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import ru.markn.dnd.network.*

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
    networkPresentation: RoomPresentation? = null,
    connectionGeneration: Long = 0,
    networkConnected: Boolean = true,
    onSpeechAck: ((RoomSpeechAcknowledgement) -> Unit)? = null,
    presentationPaused: Boolean = false,
    pausedAtMillis: Long? = null,
) {
    val pauseClock = remember(state.sessionId) { PresentationPauseClock() }
    pauseClock.setPaused(presentationPaused)
    CompositionLocalProvider(LocalPresentationPauseClock provides pauseClock) {
    var now by remember { mutableLongStateOf(storyNowMillis() + serverTimeOffsetMillis) }
    LaunchedEffect(serverTimeOffsetMillis, presentationPaused, pausedAtMillis) {
        if (presentationPaused) {
            pausedAtMillis?.let { now = it }
        } else while (true) {
            now = storyNowMillis() + serverTimeOffsetMillis
            kotlinx.coroutines.delay(16)
        }
    }
    val playback = state.playback
    val motionProfile = GameMotionProfile(LocalReducedMotion.current)
    val rolls = state.presentedRolls()
    val queue = remember(state.sessionId) { RollPresentationQueue(rolls) }
    // Include fresh authority events before the effect has appended them to the local queue.
    val presentationPending = queue.pending.isNotEmpty() || queue.hasUnseen(rolls)
    val combatPresentationPending = queue.pending.any { it.combat != null } || queue.hasUnseen(rolls.filter { it.combat != null })
    val localEnding = remember(state.sessionId) { LocalEndingPlayback() }
    SideEffect { localEnding.update(playback.endingAt, presentationPending, now) }
    val coordinated = onSpeechAck != null
    val presentation = networkPresentation?.takeIf { it.sessionId == state.sessionId }
    val endingAllowed = endingPlaybackAllowed(presentation, presentationPending)
    val voice = if (onSpeechAck != null) rememberRoomStorySpeech(state, presentation, now, speechBaseUrl,
        connectionGeneration, networkConnected, onSpeechAck, endingReady = endingAllowed, paused = presentationPaused, pausedAtMillis = pausedAtMillis)
        else rememberStorySpeech(state, now, speechBaseUrl, endingReady = !presentationPending)
    val roomCue = voice.roomCue ?: presentation?.currentCue(now)
    val showOpening = if (coordinated) presentation?.busy == true && roomCue?.kind == RoomSpeechKind.OPENING
        else now < playback.openingEndsAt
    var endingCinemaStarted by remember(state.sessionId) { mutableStateOf(false) }
    val endingCueStarted = roomCue?.kind == RoomSpeechKind.ENDING || presentation?.cues?.any {
        it.kind == RoomSpeechKind.ENDING && it.startsAt?.let { start -> start <= now } == true
    } == true
    SideEffect { if (endingCueStarted && endingAllowed) endingCinemaStarted = true }
    val showEnding = if (coordinated) state.ending != null && endingAllowed && (endingCueStarted || endingCinemaStarted)
        else state.ending != null && localEnding.startedAt != null
    val endingFinished = if (coordinated) state.ending != null && endingCueStarted && presentation?.sealed == true &&
        presentation.phase == RoomPresentationPhase.COMPLETE && !awaitingNarration && networkConnected &&
        !presentationPending && !voice.busy
        else showEnding && !awaitingNarration && localEnding.finished(now,
            state.endingShots().sumOf { subtitleDuration(it.text) }, voice.busy, presentationPending)
    val finishCallback by rememberUpdatedState(onFinished)
    LaunchedEffect(state.sessionId, endingFinished, presentationPaused) { if (endingFinished && !presentationPaused) finishCallback?.invoke() }
    // Buffered captions advance with playback, never with a response that is still synthesizing.
    val speaking = if (coordinated || voice.available) voice.current ?: voice.caption
        else playback.speeches.firstOrNull { now in it.startsAt until it.endsAt }
            ?: playback.speeches.lastOrNull { it.startsAt <= now }
    val busy = if (coordinated) presentation?.busy != false || !networkConnected else now < playback.busyUntil || voice.busy
    val combatEntrance = remember(state.sessionId) { CombatEntrance() }
    LaunchedEffect(state.combat != null, busy, awaitingNarration, presentationPaused) {
        if (!presentationPaused) combatEntrance.update(state.combat != null, busy || awaitingNarration)
    }
    val actorState = if (busy && playback.actorId != null && state.players.any { it.id == playback.actorId })
        state.copy(turnOrder = state.turnOrder.copy(currentPlayerId = playback.actorId)) else state
    // Keep the authored location, avatars and captions while the encounter introduction plays.
    val displayedState = if (combatEntrance.visible) actorState else actorState.copy(combat = null)
    val music = LocalStoryMusic.current
    val cue = when {
        showEnding -> if (state.outcome?.success == true) MusicCue.VICTORY else MusicCue.DEFEAT
        state.combat == null && combatPresentationPending -> MusicCue.COMBAT
        combatEntrance.visible -> MusicCue.COMBAT
        showOpening -> MusicCue.OPENING
        else -> MusicCue.EXPLORATION
    }
    SideEffect { music?.cue = cue; music?.roomPaused = presentationPaused; music?.pauseClock = pauseClock }
    DisposableEffect(music) { onDispose { music?.cue = MusicCue.MENU; music?.roomPaused = false; music?.pauseClock = null } }
    val effects = rememberGameEffects(state.sessionId, connectionGeneration, rolls.mapTo(mutableSetOf()) { it.id })
    GameEffectsHost(effects, connected = !coordinated || networkConnected,
        ambience = cellarAmbience(displayedState, showOpening || showEnding),
        speaking = voice.current != null, paused = presentationPaused, history = rolls.mapTo(mutableSetOf()) { it.id })
    val initiativePending = queue.pending.any { it.combat?.kind == ru.markn.dnd.combat.CombatEventKind.INITIATIVE } ||
        queue.hasUnseen(rolls.filter { it.combat?.kind == ru.markn.dnd.combat.CombatEventKind.INITIATIVE })
    LaunchedEffect(rolls) { queue.receive(rolls) }
    CompositionLocalProvider(LocalStorySpeech provides voice, LocalGameEffects provides effects) {
    Box(Modifier.fillMaxSize()) {
        PausableCrossfade(displayedState, modifier = Modifier.fillMaxSize(), contentKey = { it.combat != null },
            millis = motionProfile.transitionMillis(320)) { scene ->
          AdventureContent(scene, onSubmit, onRoll, onCancelCheck, onEndTurn, onRestart,
            controllingPlayerId, commandsEnabled && !presentationPaused && !presentationPending && !busy && !awaitingNarration &&
                (state.combat == null || combatEntrance.visible), restartEnabled,
            aiNarrator, onRollWithTalent, onCombat, statusText, onExit, onCancelNarration,
            onSkipDisconnected, onReconnect, speaking, now, !initiativePending, coordinated || voice.available)
        }
        RollPresentationHost(queue, controllingPlayerId,
            combatPresentationsEnabled = state.combat == null || combatEntrance.visible)
        if (combatEntrance.visible && busy && speaking != null) StorySubtitle(
            speaking.entry.text, state.speakerName(speaking.entry), now - speaking.startsAt,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        PausableVisibility(showOpening || showEnding, enterMillis = motionProfile.transitionMillis(600),
            exitMillis = motionProfile.transitionMillis(400)) {
            StoryCinema(state, ending = state.ending != null, now = now,
                speech = if (coordinated) speaking?.takeIf { timed -> roomCue?.let { cue ->
                    timed.entry.id == cue.entry.id && timed.entry.text == cue.entry.text
                } == true } else voice.current ?: speaking?.takeIf { state.ending != null && it.startsAt >= playback.endingEndsAt },
                coordinated = coordinated, coordinatedCue = roomCue,
                timelineStart = if (!coordinated && state.ending != null) localEnding.startedAt else null)
        }
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
    val motionProfile = GameMotionProfile(LocalReducedMotion.current)
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
    fun open(name: String) { panel = name; scope.launch {
        if (motionProfile.reduced) drawer.snapTo(DrawerValue.Open) else drawer.open()
    } }
    fun close() { scope.launch {
        if (motionProfile.reduced) drawer.snapTo(DrawerValue.Closed) else drawer.close()
    } }
    val pendingCheck = state.pendingCheck
    val subtitle = speech?.entry ?: state.lastSpokenEntry?.takeUnless { bufferedSpeech }?.let { entry ->
        entry.copy(text = subtitleSegments(entry.text).lastOrNull().orEmpty())
    }
    LaunchedEffect(state.pendingCheck?.id, canControl) {
        if (state.pendingCheck != null && canControl) {
            panel = "Проверка"
            if (motionProfile.reduced) drawer.snapTo(DrawerValue.Open) else drawer.open()
        }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 410.dp).fillMaxHeight()
            .border(1.dp, AdventureGold.copy(alpha = .24f), MaterialTheme.shapes.large),
            drawerShape = MaterialTheme.shapes.large, drawerContainerColor = AdventureInk) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(panel, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AdventureButton(secondary = true, onClick = { close() }) { Text("Закрыть") }
            }
            AdventureDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
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
                        AdventureButton(secondary = true, onClick = { selectedHero = player.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(player.hero.name + if (player.id == state.activePlayer.id) " · сейчас ходит" else "")
                        }
                    }
                    val player = state.players.firstOrNull { it.id == selectedHero } ?: state.activePlayer
                    Text(state.scenario.sites.getValue(player.location).title, color = AdventureMuted)
                    HeroPanel(player.hero, state.scenario.items)
                }
                "Действия" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.suggestions.forEach { action ->
                        action.detail?.let { Text(it, color = AdventureMuted, style = MaterialTheme.typography.bodySmall) }
                        AdventureButton(secondary = true, onClick = { close(); onSubmit(state.activePlayer.id, state.turnId, action.text) },
                            enabled = canControl && state.canAct, modifier = Modifier.fillMaxWidth()) { Text(action.text) }
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.scenario.title, style = MaterialTheme.typography.titleMedium)
                    Text(state.scenario.goal)
                    GameSettingsButton()
                    Text("${state.scenario.pressure}: ${state.checksRemaining}", color = AdventureMuted)
                    AdventureButton(secondary = true, onClick = { showRules = true }, modifier = Modifier.fillMaxWidth()) { Text("Правила и управление") }
                    if (onReconnect != null) AdventureButton(secondary = true, onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text("Подключиться снова") }
                    if (onSkipDisconnected != null) AdventureButton(secondary = true, onClick = { close(); onSkipDisconnected() }, modifier = Modifier.fillMaxWidth()) { Text("Пропустить отключившегося игрока") }
                    if (restartEnabled) AdventureButton(secondary = true, onClick = { showRestart = true }, modifier = Modifier.fillMaxWidth()) { Text("Начать заново") }
                    if (onExit != null) AdventureButton(secondary = true, onClick = { close(); onExit() }, modifier = Modifier.fillMaxWidth()) { Text("Выйти из истории") }
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
                    AdventureButton(onClick = { open("Проверка") }) { Text("Проверка") }
                }
                else -> Unit
            }
            PausableVisibility(canControl && state.canAct, enterMillis = motionProfile.transitionMillis(300),
                exitMillis = motionProfile.transitionMillis(250)) {
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
            Text("Игровые правила находятся здесь; рассказчик описывает события, а NPC говорят от своего лица. Громкость голосов, музыки и эффектов настраивается отдельно. Если озвучка недоступна, история продолжается с субтитрами последней реплики.")
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
                        Modifier.weight(1f).background(AdventurePanelColor, MaterialTheme.shapes.small)
                            .border(1.dp, AdventureGold.copy(alpha = .18f), MaterialTheme.shapes.small).padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ability.abbreviation + (hero.profile?.scores?.get(ability)?.let { " $it" } ?: ""), color = AdventureMuted, fontSize = 11.sp)
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
    val reduced = LocalReducedMotion.current
    LaunchedEffect(state.sessionId, state.journal.size, reduced) {
        if (state.journal.isNotEmpty()) {
            if (reduced) scroll.scrollToItem(state.journal.lastIndex) else scroll.animateScrollToItem(state.journal.lastIndex)
        }
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
                    if (isPlayer) Modifier.background(AdventurePanelColor, MaterialTheme.shapes.small)
                        .border(1.dp, AdventureGold.copy(alpha = .2f), MaterialTheme.shapes.small).padding(14.dp)
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
                style = MaterialTheme.typography.labelSmall,
            )
            val roll = entry.roll
            if (roll != null) {
                Row(
                    Modifier.fillMaxWidth().background(AdventurePanelColor, MaterialTheme.shapes.medium)
                        .border(1.dp, AdventureGold.copy(alpha = .3f), MaterialTheme.shapes.medium).padding(14.dp),
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
internal fun ActionComposer(
    state: AdventureState, onSubmit: (String) -> Unit, onEndTurn: () -> Unit,
    draft: String, onDraftChange: (String) -> Unit, onShowActions: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var speechOnly by rememberSaveable { mutableStateOf(false) }
    val submitted = if (speechOnly && directSpeechPresentation(draft) == null) "«${draft.trim()}»" else draft
    val valid = draft.isNotBlank() && submitted.trim().length <= AdventureEngine.MAX_ACTION_LENGTH &&
        (!speechOnly || directSpeechPresentation(submitted) != null)
    fun send() { if (valid) { onSubmit(submitted); onDraftChange(""); keyboard?.hide() } }
    Column(Modifier.fillMaxWidth().background(AdventureInk).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AdventureDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdventureButton(secondary = !speechOnly, onClick = { speechOnly = true }, modifier = Modifier.weight(1f)) {
                Text("Прямая речь")
            }
            AdventureButton(secondary = speechOnly, onClick = { speechOnly = false }, modifier = Modifier.weight(1f)) {
                Text("Действие и речь")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val field: @Composable (Modifier) -> Unit = { modifier ->
                AdventureTextField(value = draft, onValueChange = { if (it.length <= AdventureEngine.MAX_ACTION_LENGTH) onDraftChange(it) },
                    modifier = modifier, placeholder = if (speechOnly) "Только слова героя" else "Действие или смешанная реплика", maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }))
            }
            if (maxWidth < 360.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(Modifier.fillMaxWidth())
                    AdventureButton(onClick = { send() }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Отправить") }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                AdventureButton(onClick = { send() }, enabled = valid) { Text("Отправить") }
            }
        }
        Text("${state.activePlayer.hero.name} · ваш ход", color = AdventureMuted, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(shape = MaterialTheme.shapes.small, onClick = onShowActions) { Text("Действия") }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { keyboard?.hide(); onEndTurn() }) { Text("Передать ход") }
        }
    }
}
@Composable
internal fun CheckPanel(
    check: PendingCheck, state: AdventureState, onRoll: (CheckId) -> Unit, onCancel: (CheckId) -> Unit,
    onRollWithTalent: ((CheckId) -> Unit)? = null,
) {
    val proposed = requireNotNull(check.plan?.check)
    val bonuses = CharacterRules.bonuses(state.activePlayer.hero, proposed)
    AdventurePanel(Modifier.fillMaxWidth().padding(12.dp), emphasized = true) {
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
        AdventureButton(onClick = { onRoll(check.id) }, modifier = Modifier.fillMaxWidth()) { Text("Бросить d20") }
        AdventureButton(secondary = true, onClick = { onCancel(check.id) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другое действие") }
        if (CharacterRules.canUseTalent(state, proposed) && onRollWithTalent != null) {
            AdventureButton(secondary = true, onClick = { onRollWithTalent(check.id) }, modifier = Modifier.fillMaxWidth()) {
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
