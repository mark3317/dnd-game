package ru.markn.dnd.combat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.adventure.DiceEvent
import ru.markn.dnd.adventure.ui.DiceRollOverlay
import ru.markn.dnd.adventure.ui.LocalGameMotion
import ru.markn.dnd.generated.resources.*
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun CombatEventPresentation(event: CombatEvent, controllingPlayerId: String?, onDone: () -> Unit) {
    when (event.kind) {
        CombatEventKind.INITIATIVE -> CombatOpeningPresentation(event, controllingPlayerId, onDone)
        CombatEventKind.MOVE -> MovementPresentation(event, controllingPlayerId, onDone)
        else -> InteractionPresentation(event, onDone)
    }
}

@Composable
private fun CombatOpeningPresentation(event: CombatEvent, controllingPlayerId: String?, onDone: () -> Unit) {
    var rolling by remember { mutableStateOf(false) }
    val motion = LocalGameMotion.current
    LaunchedEffect(Unit) { delay(if (motion) 950 else 350); rolling = true }
    AnimatedContent(rolling, modifier = Modifier.fillMaxSize(), transitionSpec = {
        fadeIn(tween(if (motion) 350 else 0)) togetherWith fadeOut(tween(if (motion) 250 else 0))
    }, label = "battle opening") { showRoll ->
        if (!showRoll) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).clickable(onClick = {}),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("БОЙ НАЧИНАЕТСЯ", color = BattleGold, style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("Приготовьтесь · определяем инициативу", color = Color.White.copy(alpha = .8f),
                    textAlign = TextAlign.Center)
            }
        } else {
            val (own, initiative) = event.ownInitiative(controllingPlayerId)
            DiceRollOverlay(DiceEvent("Ваша инициатива", own.name, listOf(initiative.die), 20, initiative.dexterity,
                outcome = "Инициатива: ${initiative.total}"), 1,
                topContent = { revealed ->
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("БОЙ НАЧИНАЕТСЯ", color = BattleGold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(revealed, enter = slideInVertically(tween(450)) { -it } + fadeIn(tween(450))) {
                        Column {
                            Text("ПОРЯДОК ХОДОВ", color = BattleGold, fontSize = 11.sp)
                            CombatTurnStrip(event.fighters, event.initiative, event.initiative.first().fighterId)
                        }
                    }
                  }
                }, onDone = onDone)
        }
    }
}

@Composable
private fun MovementPresentation(event: CombatEvent, controllingPlayerId: String?, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo((event.path.size - 1).coerceAtLeast(0).toFloat(),
            tween((event.path.size - 1).coerceAtLeast(1) * 280, easing = LinearEasing))
        delay(150); onDone()
    }
    val index = progress.value.toInt().coerceIn(0, event.path.lastIndex.coerceAtLeast(0))
    val from = event.path.getOrNull(index) ?: event.actor.tile
    val to = event.path.getOrNull(index + 1) ?: from
    val fraction = progress.value - index
    val position = Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
    val battle = remember(event) { CombatDemo(fighters = event.fighters, activeId = event.actor.id) }
    BoxWithConstraints(Modifier.fillMaxSize().background(BattleInk).clickable(onClick = {}).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val boardWidth = ((maxHeight - 85.dp).coerceAtLeast(200.dp) * 1.2f).coerceAtMost(900.dp)
        Column(Modifier.widthIn(max = boardWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${event.actor.name} · перемещение", color = BattleGold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Battlefield(battle, movingId = event.actor.id, movingPosition = position,
                highlightTurn = !event.actor.enemy && (controllingPlayerId == null || controllingPlayerId == event.actor.id))
        }
    }
}

@Composable
private fun InteractionPresentation(event: CombatEvent, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var fast by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(fast) {
        if (fast) progress.snapTo(1f) else progress.animateTo(1f, tween(2200, easing = LinearEasing))
        revealed = true
        delay(2400); onDone()
    }
    val t = progress.value
    val supportive = event.kind in listOf(CombatEventKind.HELP, CombatEventKind.HEAL, CombatEventKind.BANDAGE, CombatEventKind.GUARD)
    val healing = event.kind in listOf(CombatEventKind.HEAL, CombatEventKind.BANDAGE)
    val impact = t >= .55f
    val actorPose = when {
        t < .25f || t > .88f -> BattlePose.IDLE
        event.kind == CombatEventKind.GUARD -> BattlePose.IDLE
        supportive -> BattlePose.HELP
        else -> BattlePose.ATTACK
    }
    val targetPose = when {
        !impact -> BattlePose.IDLE
        healing -> BattlePose.HEALED
        event.kind == CombatEventKind.HELP -> BattlePose.HELP
        event.kind == CombatEventKind.GUARD -> BattlePose.IDLE
        !event.hit -> BattlePose.DODGE
        else -> BattlePose.HURT
    }
    val target = event.target?.takeIf { it.id != event.actor.id }
    BoxWithConstraints(Modifier.fillMaxSize().background(BattleInk).clickable(onClick = {})) {
        val width = maxWidth
        Image(painterResource(Res.drawable.combat_stage_v1), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .60f), Color.Transparent, Color.Black.copy(alpha = .9f)))))
        Row(Modifier.fillMaxWidth().fillMaxHeight(.72f).align(Alignment.Center)
            .padding(top = 60.dp).padding(horizontal = width * .08f, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            BattleAvatar(event.actor, pose = if (target == null && impact && healing) BattlePose.HEALED else actorPose,
                modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                    translationX = if (!supportive && t in .25f.. .8f) sin((t - .25f) / .55f * PI).toFloat() * width.toPx() * .10f else 0f
                })
            if (target != null) BattleAvatar(target, pose = targetPose, facingLeft = true,
                modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                    translationX = if (impact && t < .88f) sin((t - .55f) / .33f * PI).toFloat() * width.toPx() * (if (event.hit) .025f else .07f) else 0f
                })
        }
        Canvas(Modifier.fillMaxSize()) {
            if (t in .40f.. .9f) {
                val intensity = sin((t - .4f) / .5f * PI).toFloat().coerceIn(0f, 1f)
                val color = if (supportive && event.kind != CombatEventKind.GUARD) BattleMint else BattleGold
                val aim = Offset(size.width * if (target == null) .5f else .72f, size.height * .57f)
                if (supportive) {
                    drawCircle(color.copy(alpha = intensity * .75f), 35.dp.toPx() + t * 70.dp.toPx(), aim, style = Stroke(3.dp.toPx()))
                    repeat(10) { i -> drawCircle(color.copy(alpha = intensity), 3.dp.toPx(),
                        aim + Offset((i % 5 - 2) * 23.dp.toPx(), (i / 5 - 1) * 45.dp.toPx() - t * 35.dp.toPx())) }
                } else {
                    drawLine(color.copy(alpha = intensity), Offset(size.width * .35f, size.height * .5f), aim, 4.dp.toPx())
                    if (event.hit && impact) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = intensity), Color.Transparent), aim, 90.dp.toPx()),
                        90.dp.toPx(), aim)
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(event.title, color = BattleGold, fontFamily = MaterialTheme.typography.headlineLarge.fontFamily, fontSize = 28.sp, textAlign = TextAlign.Center)
            Text(event.actor.name + (target?.let { " → ${it.name}" } ?: ""), color = Color.White, textAlign = TextAlign.Center)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(revealed, enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 3 },
                exit = fadeOut(tween(180))) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(interactionOutcome(event), color = if (supportive) BattleMint else BattleGold,
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                event.rolls.forEach { roll ->
                    val dice = if (roll.best) "max(${roll.dice.joinToString(", ")})" else roll.dice.joinToString(" + ")
                    Text("${roll.title}: $dice ${if (roll.bonus >= 0) "+" else "−"} ${kotlin.math.abs(roll.bonus)} = ${roll.total}" +
                        (roll.difficulty?.let { " против КЗ $it" } ?: ""), color = Color(0xFFD5DFE1), fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                }
              }
            }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { if (revealed) onDone() else fast = true }) {
                Text(if (revealed) "Продолжить" else "Ускорить", color = BattleGold)
            }
        }
    }
}

internal fun interactionOutcome(event: CombatEvent): String = when (event.kind) {
    CombatEventKind.ATTACK -> if (!event.hit) "Промах · ${event.target?.name} уклоняется" else
        "${if (event.rolls.firstOrNull()?.dice?.maxOrNull() == 20) "Критическое попадание" else "Попадание"} · ${event.target?.hp} → ${event.healthAfter} ♥"
    CombatEventKind.HEAL, CombatEventKind.BANDAGE -> "Здоровье: ${event.target?.hp} → ${event.healthAfter} ♥"
    CombatEventKind.HELP -> "Преимущество на следующую атаку"
    CombatEventKind.GUARD -> "+2 КЗ до следующего своего хода"
    else -> event.title
}
