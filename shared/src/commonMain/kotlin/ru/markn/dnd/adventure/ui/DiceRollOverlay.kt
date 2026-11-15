package ru.markn.dnd.adventure.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class PresentedRoll(val id: String, val event: DiceEvent? = null, val combat: CombatEvent? = null)

internal fun AdventureState.presentedRolls(): List<PresentedRoll> {
    val hasCombatIntro = journal.any { entry -> entry.combatEvents.any { it.kind == CombatEventKind.INITIATIVE } }
    return journal.flatMap { entry ->
    val check = entry.roll?.let { roll ->
        listOf(DiceEvent(roll.ability.title, entry.actorName.orEmpty(), listOf(roll.die), 20,
            roll.modifier, roll.difficulty, roll.succeeded, if (roll.succeeded) "Успех" else "Неудача",
            detail = roll.bonuses?.let { "Характеристика ${signedBonus(it.ability)} · специализация +${it.expertise} · талант +${it.racial}" }.orEmpty()))
    }.orEmpty()
    val dice = entry.diceEvents.filter { entry.combatEvents.isEmpty() && !(hasCombatIntro && it.title == "Инициатива") }
    (check + dice).mapIndexed { index, event -> PresentedRoll("${entry.id}:$index", event) } +
        entry.combatEvents.mapIndexed { index, event -> PresentedRoll("${entry.id}:combat:$index", combat = event) }
    }
}

/** Keeps all rolls from batched network updates, and ignores the initial historical snapshot. */
internal class RollPresentationQueue(initial: List<PresentedRoll>) {
    private val seen = initial.mapTo(mutableSetOf()) { it.id }
    var pending by mutableStateOf<List<PresentedRoll>>(emptyList())
        private set
    fun receive(rolls: List<PresentedRoll>) {
        pending = pending + rolls.filter { seen.add(it.id) }
    }
    fun hasUnseen(rolls: List<PresentedRoll>) = rolls.any { it.id !in seen }
    fun advance(id: String) { if (pending.firstOrNull()?.id == id) pending = pending.drop(1) }
}

@Composable
internal fun RollPresentationHost(queue: RollPresentationQueue, controllingPlayerId: String? = null,
    combatPresentationsEnabled: Boolean = true) {
    val current = queue.pending.firstOrNull() ?: return
    if (current.combat != null && !combatPresentationsEnabled) return
    key(current.id) {
        val profile = GameMotionProfile(LocalReducedMotion.current)
        var visible by remember { mutableStateOf(true) }
        // Keep the event mounted throughout pause, including its exit and advance barrier.
        Box(Modifier.fillMaxSize().clickable(onClick = {})) {
            PausableVisibility(visible, enterMillis = profile.transitionMillis(240),
                exitMillis = profile.transitionMillis(220), onHidden = { queue.advance(current.id) }) {
                val combat = current.combat
                val done = { visible = false }
                CompositionLocalProvider(LocalSoundPresentationId provides current.id) {
                    if (combat != null) CombatEventPresentation(combat, controllingPlayerId, done)
                    else DiceRollOverlay(requireNotNull(current.event), queue.pending.size, onDone = done)
                }
            }
        }
    }
}

@Composable
internal fun DiceRollOverlay(event: DiceEvent, remaining: Int,
    topContent: @Composable (Boolean) -> Unit = {},
    soundOutput: ((String, GameSound) -> Unit)? = null, onDone: () -> Unit) {
    val pauseClock = LocalPresentationPauseClock.current
    val motion = remember { Animatable(0f) }
    val profile = GameMotionProfile(LocalReducedMotion.current)
    var revealed by remember { mutableStateOf(false) }
    var face by remember { mutableIntStateOf(1) }
    val effects by rememberUpdatedState(LocalGameEffects.current)
    val soundId = LocalSoundPresentationId.current
    fun sound(phase: String, clip: GameSound) {
        if (soundOutput != null) soundOutput(phase, clip) else if (soundId != null) effects?.play(soundId, phase, clip)
    }
    LaunchedEffect(soundId) { if (!profile.reduced) sound("dice:cast", GameSound.DICE_CAST) }
    LaunchedEffect(revealed, soundId) { if (revealed) sound("dice:land", GameSound.DICE_LAND) }
    LaunchedEffect(revealed, profile.reduced) {
        if (!revealed) {
            if (profile.reduced) motion.snapTo(1f) else pauseClock.presentationAnimation { motion.animateTo(1f, tween(profile.diceWindupMillis, easing = LinearOutSlowInEasing)) }
            revealed = true
        }
    }
    // Mode changes after reveal do not restart the result's reading window.
    LaunchedEffect(revealed) {
        if (revealed) {
            motion.snapTo(1f)
            pauseClock.presentationDelay(profile.diceResultMillis)
            onDone()
        }
    }
    LaunchedEffect(revealed, profile.reduced) {
        var tick = 0
        while (!revealed && !profile.reduced) {
            face = (tick * 7 + 3) % event.sides + 1
            tick++
            pauseClock.presentationDelay(65L + (motion.value * 90).toLong())
        }
    }
    val accent = when (event.success) {
        true -> AdventureGreen
        false -> Color(0xFFF2A79C)
        null -> AdventureGold
    }
    BoxWithConstraints(Modifier.fillMaxSize()
        .background(Brush.radialGradient(listOf(Color(0xFF101B23).copy(alpha = .64f), Color.Black.copy(alpha = .84f))))
        .clickable(enabled = true, onClick = {})
        .safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 560.dp
        val dieSize = if (compact) 150.dp else 240.dp
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .42f
            drawCircle(AdventureGold.copy(alpha = .12f), radius, style = Stroke(1.dp.toPx()))
            drawCircle(AdventureGold.copy(alpha = .06f), radius + 12.dp.toPx(), style = Stroke(1.dp.toPx()))
            repeat(36) { i ->
                val angle = i * PI / 18
                val r = radius * (0.6f + (i % 5) * .1f)
                val point = center + Offset(cos(angle).toFloat() * r, sin(angle).toFloat() * r)
                drawCircle(AdventureGold.copy(alpha = .15f + (i % 3) * .08f), (1 + i % 2).dp.toPx(), point)
            }
        }
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp).animateContentSize(tween(profile.transitionMillis(220))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp)) {
            topContent(revealed || profile.reduced)
            Text("Н И Т И   С У Д Ь Б Ы", color = AdventureGold, fontSize = 11.sp)
            Text(event.title, color = Color(0xFFF0E9DD), fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                fontSize = if (compact) 24.sp else 32.sp, textAlign = TextAlign.Center)
            Text(event.actor, color = AdventureMuted, textAlign = TextAlign.Center)
            if (event.difficulty != null) Text("НУЖНО ${event.difficulty}", color = AdventureGold,
                style = MaterialTheme.typography.titleMedium)
            Box(Modifier.size(dieSize).graphicsLayer {
                val t = if (profile.reduced) 1f else motion.value
                rotationZ = (1f - t) * 610f
                rotationY = (1f - t) * 720f
                val scale = .65f + t * .35f
                scaleX = scale; scaleY = scale
                translationY = -sin(t * PI * 3).toFloat() * (1f - t) * 65f
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension * .43f
                    val points = (0..5).map { i ->
                        val a = PI / 3 * i - PI / 2
                        center + Offset(cos(a).toFloat() * r, sin(a).toFloat() * r)
                    }
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }; close()
                    }
                    drawCircle(Brush.radialGradient(listOf(AdventureGold.copy(alpha = .22f), Color.Transparent)), r * 1.16f)
                    drawPath(path, Brush.linearGradient(listOf(Color(0xFF54707A), Color(0xFF152B39), Color(0xFF59442C))))
                    drawPath(path, AdventureGold, style = Stroke(2.dp.toPx()))
                    // Triangular facets frame the central numbered face.
                    for (i in listOf(0, 2, 4)) {
                        drawLine(AdventureGold.copy(alpha = .65f), points[i], points[(i + 2) % 6], 1.dp.toPx())
                        drawLine(AdventureGold.copy(alpha = .4f), points[i], points[(i + 5) % 6], 1.dp.toPx())
                    }
                }
                Text(if (revealed || profile.reduced) (if (event.best) event.dice.max() else event.dice.first()).toString() else face.toString(),
                    color = Color(0xFFFFE4B3), fontSize = if (compact) 46.sp else 68.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MaterialTheme.typography.headlineLarge.fontFamily)
            }
            PausableCrossfade(revealed || profile.reduced, millis = profile.transitionMillis(260)) { showResult ->
              Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(if (showResult) event.outcome else "Кубик в полёте…",
                    color = if (showResult) accent else AdventureGold, fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (showResult) {
                    val values = if (event.best) "max(${event.dice.joinToString(", ")})" else event.dice.joinToString(" + ")
                    Text("$values ${signedBonus(event.bonus)} = ${event.total}", color = Color(0xFFF0E9DD),
                        fontSize = 21.sp, textAlign = TextAlign.Center)
                    if (event.best) Text("Преимущество · выбран лучший кубик", color = AdventureMuted)
                } else Text("${event.dice.size}d${event.sides} ${signedBonus(event.bonus)}", color = AdventureMuted)
              }
            }
            PausableVisibility((revealed || profile.reduced) && event.detail.isNotEmpty(),
                enterMillis = profile.transitionMillis(220), exitMillis = profile.transitionMillis(220)) {
                Text(event.detail, color = AdventureMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { if (revealed) onDone() else revealed = true }) {
                Text(if (revealed) "Продолжить" else "Показать результат", color = AdventureGold)
            }
            if (remaining > 1) Text("Ещё бросков: ${remaining - 1}", color = AdventureMuted, fontSize = 11.sp)
        }
    }
}
