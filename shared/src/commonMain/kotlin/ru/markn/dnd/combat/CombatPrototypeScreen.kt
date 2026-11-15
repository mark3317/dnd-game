package ru.markn.dnd.combat

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import ru.markn.dnd.adventure.ui.PausableVisibility
import ru.markn.dnd.adventure.ui.PausableCrossfade
import ru.markn.dnd.adventure.ui.LocalPresentationPauseClock
import ru.markn.dnd.adventure.ui.presentationDelay
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.generated.resources.*
import kotlin.math.roundToInt

internal val BattleInk = Color(0xFF101B23)
internal val BattleGold = Color(0xFFE8B975)
internal val BattleMint = Color(0xFF9CC8AB)
private val Muted = Color(0xFFB2C2C7)

@Composable
fun CombatPrototypeScreen(onBack: () -> Unit, battleState: CombatDemo? = null,
    onCommand: ((DemoAction?, Tile?) -> Unit)? = null, commandsEnabled: Boolean = true, onReconnect: (() -> Unit)? = null,
    initiativeVisible: Boolean = true, statusText: String? = null) {
    var teaching by remember { mutableStateOf(if (battleState == null) CombatDemo.start() else null) }
    val battle = battleState ?: requireNotNull(teaching)
    val motionProfile = GameMotionProfile(LocalReducedMotion.current)
    val music = LocalStoryMusic.current
    if (battleState == null) {
        SideEffect { music?.cue = if (battle.finished) MusicCue.EXPLORATION else MusicCue.COMBAT }
        DisposableEffect(music) { onDispose { music?.cue = MusicCue.MENU } }
    }
    var generation by remember { mutableIntStateOf(0) }
    val effects = if (battleState == null) rememberGameEffects(-1L, generation.toLong(), emptySet()) else LocalGameEffects.current
    val events = if (battleState == null) battle.events.mapIndexed { index, event -> PresentedRoll(index.toString(), combat = event) } else emptyList()
    if (battleState == null && effects != null) GameEffectsHost(effects, history = events.mapTo(mutableSetOf()) { it.id })
    val queue = if (battleState == null) remember(generation) { RollPresentationQueue(emptyList()).apply { receive(events) } } else null
    if (queue != null) LaunchedEffect(events, generation) { queue.receive(events) }
    val enabled = commandsEnabled && (queue?.pending?.isEmpty() != false)
    var selected by remember { mutableStateOf<Tile?>(null) }
    var action by remember { mutableStateOf<DemoAction?>(null) }
    var hint by remember { mutableStateOf<Pair<Offset, String>?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    LaunchedEffect(battle.log.size) { showLog = false; showHelp = false }
    LaunchedEffect(battle.activeId, generation) { selected = null; action = null }
    val pauseClock = LocalPresentationPauseClock.current
    LaunchedEffect(hint) { if (hint != null) { pauseClock.presentationDelay(3200); hint = null } }
    if (queue != null) LaunchedEffect(battle, queue.pending) {
        if (queue.pending.isEmpty() && !battle.finished && battle.active.enemy) {
            pauseClock.presentationDelay(500); teaching = battle.enemyTurn()
        }
    }
    val command = { command: DemoAction?, tile: Tile? ->
        if (enabled && battle.playerTurn) {
            if (onCommand != null) onCommand(command, tile)
            else teaching = if (command == null) battle.endTurn() else battle.execute(command, tile).completeExhaustedTurn()
            selected = null; action = null; hint = null
        }
    }
    // Lambda captures are refreshed when availability changes without a new battle snapshot.
    // A reference to a local function can retain the lock from the opening presentation.
    val doubleTap = { tile: Tile, point: Offset ->
        val quick = battle.quickMapAction(tile)
        if (quick == null) {
            selected = tile; action = DemoAction.ESCORT
            hint = point to battle.preview(DemoAction.ESCORT, tile).detail
        } else {
            val preview = battle.preview(quick, tile)
            if (!enabled) hint = point to "Сейчас нельзя действовать: дождитесь своего хода и завершения анимации."
            else if (!preview.valid) hint = point to preview.detail
            else command(quick, tile)
        }
    }
    CompositionLocalProvider(LocalGameEffects provides effects) {
    Box(Modifier.fillMaxSize().background(BattleInk)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(shape = MaterialTheme.shapes.small, onClick = onBack) { Text(if (battleState == null) "‹ Назад" else "Выйти из истории") }
                GameSettingsButton()
                if (onReconnect != null) TextButton(shape = MaterialTheme.shapes.small, onClick = onReconnect) { Text("Связь") }
                if (battleState == null) TextButton(shape = MaterialTheme.shapes.small, onClick = { generation++; teaching = CombatDemo.start() }) { Text("Заново") }
            }
            if (statusText != null) Text(statusText, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            EscortObjectivePanel(battle)
            PausableVisibility(initiativeVisible && (queue?.pending?.none { it.combat?.kind == CombatEventKind.INITIATIVE } != false),
                enterMillis = motionProfile.transitionMillis(260), exitMillis = motionProfile.transitionMillis(180)) {
                CombatTurnStrip(battle.fighters, battle.initiative, battle.activeId)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val turnTitle = when { battle.finished -> if (battle.escortObjective?.phase == EscortPhase.ESCAPED) "Повозка выехала · копии обеспечены"
                        else if (battle.victory) "Победа отряда" else "Отряд повержен"
                    else -> "Раунд ${battle.round} · ${battle.active.name}" }
                PausableCrossfade(turnTitle, modifier = Modifier.weight(1f), millis = motionProfile.transitionMillis(220)) { title ->
                    Text(title, color = BattleGold, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { showHelp = true }) { Text("Управление") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { showLog = true }) { Text("Журнал") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= 900.dp && !phone
                val canPreviewPhone = battleState == null && maxWidth >= 700.dp
                val boardWidth = if (wide) ((maxHeight - 65.dp).coerceAtLeast(220.dp) * 1.2f).coerceAtMost(880.dp) else 760.dp
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (canPreviewPhone) TextButton(shape = MaterialTheme.shapes.small, onClick = { phone = !phone }) { Text(if (phone) "Большой экран" else "Телефон") }
                    Row(Modifier.widthIn(max = if (phone) 390.dp else 1200.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.widthIn(max = boardWidth)) {
                                Battlefield(battle, selected, hint = hint,
                                    highlightTurn = enabled && battle.playerTurn,
                                    onSelect = { tile -> selected = tile; action = battle.contextActions(tile).firstOrNull(); hint = null },
                                    onDoubleTap = doubleTap)
                            }
                            Text(if (battle.escortObjective == null) "Двойное нажатие: свободная клетка — идти · враг — атаковать"
                                else "Повозка: выбрать и нажать «Продвинуть» · двойное нажатие не тратит действие", Modifier.padding(vertical = 8.dp),
                                color = Muted, style = MaterialTheme.typography.labelSmall)
                            if (!wide) CombatControls(battle, selected, action, enabled,
                                { action = it }, { action?.let { command(it, selected) } }, { command(null, null) })
                        }
                        if (wide) Column(Modifier.width(290.dp)) {
                            CombatControls(battle, selected, action, enabled,
                                { action = it }, { action?.let { command(it, selected) } }, { command(null, null) })
                            Text(battle.escortPanelText()?.detail ?: "Защитите документы от налётчиков. После боя история продолжится.", color = Muted)
                        }
                    }
                }
            }
        }
        if (queue != null) RollPresentationHost(queue, battle.fighters.first { !it.enemy }.id)
    }
    if (showLog) AlertDialog(onDismissRequest = { showLog = false }, title = { Text("Журнал боя") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { battle.log.forEach { Text(it, Modifier.padding(vertical = 5.dp)) } } },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showLog = false }) { Text("Закрыть") } })
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("Управление боем") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Доступные клетки подсвечены в свой ход. Дважды нажмите свободную клетку, чтобы пройти до 3 шагов. При недоступном пути объяснение появится под нажатием.")
            Text("Нажмите врага для атаки или боевого приёма; двойное нажатие сразу атакует. Нажмите союзника для помощи или повязки, себя — для защиты и лечения. Предварительный результат виден до выполнения.")
            Text("За ход доступны одно действие и одно перемещение. Когда оба потрачены, ход заканчивается автоматически; оставшиеся ресурсы можно пропустить кнопкой.")
            Text("Атака: d20 + бонус против КЗ. Натуральная 1 — промах, 20 — крит с двойными костями. Помощь даёт преимущество следующей атаке, защита +2 КЗ до своего следующего хода.")
            Text("Повязка лечит 4 здоровья за действие и предмет; выбывшего может поднять союзник один раз за бой. После победы у всех героев минимум 4 здоровья, повязки не пополняются.")
            if (battle.escortObjective != null) Text("Повозка — отдельный объект без здоровья и хода. Герой рядом выбирает её и нажимает «Продвинуть повозку»: 1 основное действие без броска, герой остаётся на месте. Освобождайте отмеченный маршрут. До конца 6-го раунда нужно вывести повозку; иначе продолжится тот же бой за архив, а путь копий закроется.")
        } }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showHelp = false }) { Text("Понятно") } })
    }
}

@Composable
internal fun CombatTurnStrip(fighters: List<DemoFighter>, initiative: List<DemoInitiative>, activeId: String) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        initiative.forEach { roll ->
            val fighter = fighters.first { it.id == roll.fighterId }
            val active = fighter.id == activeId
            Surface(color = if (active) Color(0xFF54442E) else BattleInk.copy(alpha = .86f), shape = RoundedCornerShape(9.dp),
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) BattleGold else Muted.copy(alpha = .25f))) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BattleAvatar(fighter, modifier = Modifier.size(34.dp), animateIdle = false)
                    Column {
                        Text(if (active) "▶ ${fighter.name}" else fighter.name, color = if (fighter.enemy) Color(0xFFF2A79C) else BattleMint, fontSize = 12.sp)
                        Text("${roll.total} · ${fighter.hp}/${fighter.maxHp} ♥", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CombatControls(battle: CombatDemo, selected: Tile?, action: DemoAction?, enabled: Boolean,
    onAction: (DemoAction) -> Unit, onExecute: () -> Unit, onEnd: () -> Unit) {
    val profile = GameMotionProfile(LocalReducedMotion.current)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).animateContentSize(tween(profile.transitionMillis(180))), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${battle.active.name} · действие ${if (battle.acted) 0 else 1}/1 · перемещение ${if (battle.moved) 0 else 1}/1",
            color = BattleMint, style = MaterialTheme.typography.labelMedium)
        val target = selected?.let(battle::occupant)
        val cart = battle.escortObjective?.takeIf { it.tile == selected }
        Text(if (cart != null) "Повозка · ${cart.tile.label} · ${cart.step}/${cart.definition.path.lastIndex} продвижений"
            else target?.let { "${it.name} · ${it.hp}/${it.maxHp} ♥ · КЗ ${it.armor}" }
                ?: if (battle.escortObjective != null) "Выберите героя, противника или повозку" else "Выберите существо или дважды нажмите клетку", color = Muted)
        battle.contextActions(selected).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { candidate ->
                    FilterChip(selected = candidate == action, onClick = { onAction(candidate) }, enabled = enabled && battle.playerTurn,
                        label = { Text(if (candidate == DemoAction.SKILL) battle.skillName else candidate.title) })
                }
            }
        }
        if (action != null) {
            val preview = battle.preview(action, selected)
            Text(preview.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(preview.cost, color = BattleGold, style = MaterialTheme.typography.labelSmall)
            AdventureButton(onClick = onExecute, enabled = enabled && preview.valid, modifier = Modifier.fillMaxWidth()) {
                Text(if (action == DemoAction.SKILL) battle.skillName else action.title)
            }
        }
        if (target != null && !target.enemy) Text("Повязки: ${target.bandages} · ${battle.recoveryStatus(target)}", color = Muted, fontSize = 11.sp)
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onEnd, enabled = enabled && battle.playerTurn, modifier = Modifier.fillMaxWidth()) {
            Text(if (battle.finished) "Бой завершён" else if (battle.active.enemy) "Ход противника…" else "Завершить ход →")
        }
    }
}

@Composable
internal fun Battlefield(battle: CombatDemo, selected: Tile? = null, hint: Pair<Offset, String>? = null,
    movingId: String? = null, movingPosition: Offset? = null,
    escortPosition: Offset? = null,
    highlightTurn: Boolean = false,
    onSelect: (Tile) -> Unit = {}, onDoubleTap: (Tile, Offset) -> Unit = { _, _ -> }) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(6f / 5f).border(1.dp, BattleGold.copy(alpha = .5f), RoundedCornerShape(10.dp))) {
        val width = maxWidth; val height = maxHeight
        val boardConstraints = constraints
        val select by rememberUpdatedState(onSelect)
        val doubleTap by rememberUpdatedState(onDoubleTap)
        Image(painterResource(Res.drawable.combat_field_v1), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)))
        battle.escortObjective?.let { EscortRoute(it, Modifier.fillMaxSize()) }
        val pulse = if (LocalReducedMotion.current) 1f else ru.markn.dnd.adventure.ui.pausedLoop(.4f, .95f, 650)
        Box(Modifier.fillMaxSize().pointerInput(battle.activeId) {
            fun tile(point: Offset) = Tile((point.x / size.width * 6).toInt().coerceIn(0, 5), (point.y / size.height * 5).toInt().coerceIn(0, 4))
            val taps = BattleTileTaps(viewConfiguration.doubleTapTimeoutMillis)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val up = waitForUpOrCancellation()
                if (up != null) {
                    up.consume()
                    val destination = tile(up.position)
                    if (taps.release(destination, up.uptimeMillis)) doubleTap(destination, up.position)
                    else select(destination)
                } else taps.reset()
            }
        }) {
            CombatDemo.tiles.forEach { tile ->
                val reachable = highlightTurn && battle.preview(DemoAction.MOVE, tile).valid
                val objective = battle.escortObjective
                val nextCartTile = objective?.takeIf { it.phase == EscortPhase.ACTIVE }?.let { it.definition.path.getOrNull(it.step + 1) }
                val blockedRoute = tile == nextCartTile && battle.occupant(tile) != null
                Box(Modifier.offset(width / 6 * tile.x, height / 5 * tile.y).size(width / 6, height / 5)
                    .background(if (tile == selected) BattleGold.copy(alpha = .26f) else if (blockedRoute) Color(0xFFF2A79C).copy(alpha = .25f)
                        else if (reachable) BattleMint.copy(alpha = .14f) else Color.Transparent)
                    .border(if (tile == selected) 2.dp else .5.dp, if (tile == selected) BattleGold else Color.White.copy(alpha = .18f))
                    .semantics { contentDescription = "${tile.label}${if (reachable) ", можно пройти" else ""}${if (blockedRoute) ", путь повозки занят" else ""}" }) {
                    Text(tile.label, Modifier.align(Alignment.TopStart).padding(3.dp), color = Color.White.copy(alpha = .65f), fontSize = 9.sp)
                    if (tile in CombatDemo.walls) Text("▧", Modifier.align(Alignment.Center), color = Muted)
                    if (tile in CombatDemo.coverTiles) Text("◒", Modifier.align(Alignment.BottomEnd).padding(3.dp), color = BattleGold)
                    if (reachable) Box(Modifier.size(5.dp).align(Alignment.BottomCenter).background(BattleMint, CircleShape))
                    if (tile == objective?.definition?.path?.lastOrNull()) Text("ВЫЕЗД", Modifier.align(Alignment.BottomCenter),
                        color = BattleGold, fontSize = 8.sp)
                }
            }
            battle.escortObjective?.takeIf { it.phase != EscortPhase.ESCAPED || escortPosition != null }?.let { objective ->
                val pos = escortPosition ?: Offset(objective.tile.x.toFloat(), objective.tile.y.toFloat())
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    EscortCart(objective, selected == objective.tile,
                        Modifier.offset(width / 6 * pos.x, height / 5 * pos.y).size(width / 6, height / 5),
                        onSelect = if (escortPosition == null) ({ select(objective.tile) }) else null)
                }
            }
            battle.fighters.filter { it.hp > 0 || !it.enemy }.forEach { fighter ->
                val pos = if (fighter.id == movingId && movingPosition != null) movingPosition else Offset(fighter.tile.x.toFloat(), fighter.tile.y.toFloat())
                val active = highlightTurn && fighter.id == battle.activeId
                Column(Modifier.offset(width / 6 * pos.x, height / 5 * pos.y).size(width / 6, height / 5), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (active) Text("▼ ХОД", color = BattleGold, fontWeight = FontWeight.Bold, fontSize = 10.sp) else Spacer(Modifier.height(14.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (active) Box(Modifier.fillMaxSize(.85f).border(3.dp, BattleGold.copy(alpha = pulse), CircleShape).background(BattleGold.copy(alpha = .1f), CircleShape))
                        BattleAvatar(fighter, modifier = Modifier.fillMaxSize(), pose = if (fighter.hp == 0) BattlePose.HURT else BattlePose.IDLE, facingLeft = fighter.enemy)
                    }
                    Text("${fighter.name} · ${fighter.hp} ♥", color = if (fighter.enemy) Color(0xFFFFB5A9) else Color(0xFFD7FFE2), fontSize = 10.sp, maxLines = 1,
                        modifier = Modifier.background(BattleInk.copy(alpha = .8f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp))
                }
            }
            if (hint != null) Surface(Modifier.offset { IntOffset(hint.first.x.roundToInt().coerceIn(0, (boardConstraints.maxWidth - 220.dp.roundToPx()).coerceAtLeast(0)),
                (hint.first.y.roundToInt() + 10.dp.roundToPx()).coerceAtMost((boardConstraints.maxHeight - 70.dp.roundToPx()).coerceAtLeast(0))) }.widthIn(max = 220.dp),
                color = BattleInk, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BattleGold)) {
                Text(hint.second, Modifier.padding(8.dp), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
