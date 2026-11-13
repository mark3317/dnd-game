package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
) {
    val ending = state.ending
    val pendingCheck = state.pendingCheck
    var showRestart by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showHero by remember { mutableStateOf(false) }
    var actionDraft by remember(state.turnId) { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 900.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ИСТОРИИ У КОСТРА", color = AdventureGold, fontSize = 10.sp, letterSpacing = 2.sp)
                        Text("Погасший маяк", fontFamily = FontFamily.Serif, fontSize = if (wide) 28.sp else 22.sp)
                    }
                    TextButton(onClick = { showRules = true }) { Text("Правила") }
                    TextButton(onClick = { showRestart = true }, enabled = restartEnabled) { Text("Заново") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) {
                        Column(
                            Modifier.width(290.dp).fillMaxHeight()
                                .verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            LighthouseArt(Modifier.fillMaxWidth().height(150.dp))
                            PartyPanel(state)
                            HeroPanel(state.activePlayer.hero)
                            Text(
                                "ЦЕЛЬ ПРИКЛЮЧЕНИЯ",
                                color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            )
                            Text("Предупредить паром об отмели — вернуть свет маяка или подать сигнал.")
                            Text(
                                if (aiNarrator) "Claude ведёт диалоги и понимает свободный текст в рамках пробного сюжета. Правила и броски выполняет сервер."
                                else "Короткий пробный сюжет. Ведущий отвечает по сценарию.",
                                color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (!wide) {
                            PartyPanel(state, compact = true, onShowHero = { showHero = true })
                        }
                        SceneHeading(state, compact = !wide)
                        Journal(state, Modifier.weight(1f).fillMaxWidth())
                        when {
                            ending != null && restartEnabled -> EndingPanel(ending, onRestart)
                            ending != null -> Text(ending.description + "\nПерезапустить историю может ведущий комнаты.", Modifier.padding(20.dp))
                            !commandsEnabled || (controllingPlayerId != null && controllingPlayerId != state.activePlayer.id) ->
                                Text(if (!commandsEnabled) "Ожидаем соединение или ответ сервера…" else "Сейчас ходит " + state.activePlayer.name + ". Дождитесь своей очереди.", Modifier.fillMaxWidth().padding(20.dp))
                            pendingCheck != null -> CheckPanel(
                                pendingCheck, state.activePlayer.hero,
                                onRoll = { onRoll(state.activePlayer.id, it) },
                                onCancel = { onCancelCheck(state.activePlayer.id, it) },
                            )
                            else -> ActionComposer(
                                state,
                                onSubmit = { onSubmit(state.activePlayer.id, state.turnId, it) },
                                onEndTurn = { onEndTurn(state.activePlayer.id, state.turnId) },
                                aiNarrator = aiNarrator,
                                draft = actionDraft,
                                onDraftChange = { actionDraft = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHero) {
        AlertDialog(
            onDismissRequest = { showHero = false },
            title = { Text(state.activePlayer.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HeroPanel(state.activePlayer.hero)
                }
            },
            confirmButton = { TextButton(onClick = { showHero = false }) { Text("Закрыть") } },
        )
    }
    if (showRestart) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            title = { Text("Начать историю заново?") },
            text = { Text("Текущий прогресс этого приключения будет сброшен.") },
            confirmButton = {
                TextButton(onClick = { showRestart = false; onRestart() }) { Text("Начать заново") }
            },
            dismissButton = { TextButton(onClick = { showRestart = false }) { Text("Продолжить") } },
        )
    }
    if (showRules) {
        AlertDialog(
            onDismissRequest = { showRules = false },
            title = { Text("Правила пробного приключения") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Для рискованного действия: d20 + модификатор характеристики. Итог не ниже сложности — успех. Особых эффектов для 1 и 20 пока нет.")
                    Text("Играйте по очереди: по сети каждый управляет своим героем, локально — на одном устройстве. Завершённое действие передаёт ход следующему участнику. Действие с кубиком завершается после броска; отмена до броска сохраняет ход. Кнопка «Передать ход» позволяет пропустить очередь.")
                    Text("У каждого героя свои характеристики, здоровье и позиция. Каждая проверка расходует одну из трёх общих попыток до прибытия парома. Осмотр, разговор и пропуск запас проверок не расходуют.")
                    Text("Сигнал фонарём или починка маяка завершают историю успехом. Неудача на лестнице отнимает 2 HP. Если попытки закончились, паром попадает на мель.")
                    Text(if (aiNarrator) "Это предварительные правила одного эпизода. Опишите осмотр, разговор, подъём, починку или сигнал своими словами. Другие действия ведущий предложит уточнить."
                        else "Это предварительные правила одного эпизода. Ведущий пока понимает только действия из подсказок — их также можно написать вручную.")
                }
            },
            confirmButton = { TextButton(onClick = { showRules = false }) { Text("Понятно") } },
        )
    }
}

@Composable
private fun PartyPanel(state: AdventureState, compact: Boolean = false, onShowHero: (() -> Unit)? = null) {
    val players = state.turnOrder.playerIds.map { id -> state.players.first { it.id == id } }
    if (compact) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(players, key = { it.id }) { player -> PartyMember(player, state, compact = true) }
            if (onShowHero != null) {
                item { TextButton(onClick = onShowHero) { Text("Персонаж") } }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ОЧЕРЕДЬ ИГРОКОВ", color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.5.sp)
            players.forEach { PartyMember(it, state, compact = false) }
        }
    }
}

@Composable
private fun PartyMember(player: Player, state: AdventureState, compact: Boolean) {
    val active = player.id == state.activePlayer.id && state.ending == null
    Column(
        Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
            .border(1.dp, if (active) AdventureGold else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "${state.turnOrder.playerIds.indexOf(player.id) + 1}. ${player.hero.name}${if (active) " · сейчас ходит" else ""}",
            color = if (active) AdventureGold else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
        if (!compact) {
            Text(
                "${player.name} · ${player.hero.health}/${player.hero.maxHealth} HP",
                color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
            )
            Text(player.location.title, color = AdventureMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SceneHeading(state: AdventureState, compact: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.ending == null) "ТУМАННЫЙ БЕРЕГ" else "ЭПИЛОГ",
                color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f),
            )
            Text(
                if (state.ending != null) "История завершена" else "Проверок до парома: ${state.checksRemaining}",
                color = if (state.checksRemaining <= 1 && state.ending == null) MaterialTheme.colorScheme.error else AdventureMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(state.ending?.title ?: state.location.title, fontFamily = FontFamily.Serif, fontSize = 22.sp)
        if (state.ending == null) {
            Text(
                "Ход: ${state.activePlayer.name} · ${state.activePlayer.hero.name} · Круг ${state.turnOrder.roundNumber}",
                color = AdventureGreen, style = MaterialTheme.typography.labelLarge,
            )
        }
        if (!compact) {
            Text(
                "Вернуть свет или предупредить паром другим способом.",
                color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HeroPanel(hero: Hero) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("ВАШ ПЕРСОНАЖ", color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text(hero.name, fontFamily = FontFamily.Serif, fontSize = 30.sp)
            Text("${hero.archetype} · готовый герой", color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
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
                        Text(ability.abbreviation, color = AdventureMuted, fontSize = 10.sp)
                        Text(signed(hero.modifier(ability)), fontSize = 20.sp, color = AdventureGold)
                    }
                }
            }
        }
        Text("СНАРЯЖЕНИЕ", color = AdventureMuted, fontSize = 10.sp, letterSpacing = 1.sp)
        hero.inventory.forEach { Text("— $it", style = MaterialTheme.typography.bodySmall) }
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
            JournalItem(entry)
        }
    }
}

@Composable
private fun JournalItem(entry: JournalEntry) {
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
                    EntryKind.NARRATOR -> listOfNotNull("ВЕДУЩИЙ", entry.actorName).joinToString(" · ")
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
    aiNarrator: Boolean, draft: String, onDraftChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = draft.isNotBlank() && draft.trim().length <= AdventureEngine.MAX_ACTION_LENGTH
    fun send() {
        if (valid) { onSubmit(draft); if (!aiNarrator) onDraftChange(""); keyboard?.hide() }
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TextButton(onClick = { keyboard?.hide(); onEndTurn() }) { Text("Передать ход", fontSize = 12.sp) }
            }
            items(state.suggestions) { action ->
                OutlinedButton(onClick = { onDraftChange(if (aiNarrator) action.text else ""); keyboard?.hide(); onSubmit(action.text) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(action.text, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= AdventureEngine.MAX_ACTION_LENGTH) onDraftChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Что делает ${state.activePlayer.hero.name}?") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(12.dp),
            )
            Button(onClick = { send() }, enabled = valid, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                Text("Отправить")
            }
        }
        Text(
            if (aiNarrator) "Опишите действие своими словами или выберите подсказку."
            else "Пробный ведущий: выберите подсказку или напишите её текст.",
            color = AdventureMuted, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CheckPanel(
    check: PendingCheck, hero: Hero, onRoll: (CheckId) -> Unit, onCancel: (CheckId) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("НУЖНА ПРОВЕРКА", color = AdventureGold, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(check.description)
        Text(
            "${check.ability.title} · d20 ${signed(hero.modifier(check.ability))} · сложность ${check.difficulty}",
            color = AdventureMuted, style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onRoll(check.id) }) { Text("Бросить d20") }
            TextButton(onClick = { onCancel(check.id) }) { Text("Выбрать другое действие") }
        }
    }
}

@Composable
private fun EndingPanel(ending: Ending, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (ending == Ending.FERRY_LOST) "Приключение окончено" else "Цель достигнута",
            color = if (ending == Ending.FERRY_LOST) MaterialTheme.colorScheme.error else AdventureGreen,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Попробуйте другой путь или начните новую попытку.", color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRestart) { Text("Пройти ещё раз") }
    }
}

@Composable
private fun LighthouseArt(modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF1B303B), RoundedCornerShape(16.dp))) {
        val w = size.width
        val h = size.height
        drawCircle(Color(0xFFBACAC6).copy(alpha = 0.25f), h * 0.17f, Offset(w * 0.25f, h * 0.29f))
        val beam = Path().apply {
            moveTo(w * 0.66f, h * 0.29f)
            lineTo(w * 0.02f, h * 0.02f)
            lineTo(w * 0.02f, h * 0.65f)
            close()
        }
        drawPath(beam, Brush.horizontalGradient(listOf(Color.Transparent, AdventureGold.copy(alpha = 0.12f))))
        drawRect(Color(0xFF0F212A), Offset(0f, h * 0.77f), Size(w, h * 0.23f))
        val tower = Path().apply {
            moveTo(w * 0.58f, h * 0.77f)
            lineTo(w * 0.61f, h * 0.37f)
            lineTo(w * 0.72f, h * 0.37f)
            lineTo(w * 0.76f, h * 0.77f)
            close()
        }
        drawPath(tower, Color(0xFF8D9B9E))
        drawRect(AdventureGold.copy(alpha = 0.65f), Offset(w * 0.61f, h * 0.24f), Size(w * 0.11f, h * 0.12f))
        drawLine(Color(0xFF0F212A), Offset(w * 0.585f, h * 0.23f), Offset(w * 0.745f, h * 0.23f), 5.dp.toPx())
        drawLine(Color(0xFF0F212A), Offset(w * 0.58f, h * 0.38f), Offset(w * 0.75f, h * 0.38f), 5.dp.toPx())
        repeat(3) { index ->
            val y = h * (0.84f + index * 0.055f)
            drawLine(Color(0xFF54717D).copy(alpha = 0.35f), Offset(w * 0.12f, y), Offset(w * 0.85f, y), 1.dp.toPx())
        }
    }
}

private fun signed(value: Int): String = if (value >= 0) "+$value" else "−${-value}"
@Preview
@Composable
private fun AdventurePreview() {
    AdventureTheme {
        AdventureScreen(
            state = AdventureEngine().newGame(),
            onSubmit = { _, _, _ -> },
            onRoll = { _, _ -> },
            onCancelCheck = { _, _ -> },
            onEndTurn = { _, _ -> },
            onRestart = {},
        )
    }
}
