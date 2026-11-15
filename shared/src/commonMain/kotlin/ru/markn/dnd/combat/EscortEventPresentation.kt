package ru.markn.dnd.combat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.markn.dnd.adventure.ui.LocalPresentationPauseClock
import ru.markn.dnd.adventure.ui.presentationDelay
import ru.markn.dnd.adventure.ui.presentationAnimation
import ru.markn.dnd.adventure.ui.*

internal fun escortEventPath(event: CombatEvent): List<Tile> {
    if (event.kind != CombatEventKind.ESCORT_MOVE) return emptyList()
    if (event.path.isNotEmpty()) return event.path
    val before = event.escortBefore ?: return emptyList()
    val after = event.escortAfter ?: return emptyList()
    return before.definition.path.subList(before.step, after.step + 1)
}

/** Departure can finish outside the frame without changing authoritative tile/hero positions. */
internal fun escortAnimationPoints(event: CombatEvent): List<Offset> {
    val points = escortEventPath(event).map { Offset(it.x.toFloat(), it.y.toFloat()) }
    if (points.isEmpty() || event.escortAfter?.phase != EscortPhase.ESCAPED) return points
    val last = points.last()
    val direction = if (points.size > 1) last - points[points.lastIndex - 1] else Offset(1f, 0f)
    return points + (last + direction)
}

internal fun escortOutcome(event: CombatEvent): String = when {
    event.kind == CombatEventKind.ESCORT_EXPIRED -> "Выезд закрыт. Продолжайте тот же бой за архив; путь копий теперь недоступен."
    event.escortAfter?.phase == EscortPhase.ESCAPED -> "Егор вывез номера грамот и обеспечит копии. Оригиналы остались в архиве."
    else -> event.escortAfter?.let { "Повозка: ${it.step}/${it.definition.path.lastIndex}. Герой остаётся на своей клетке." } ?: event.title
}

/** Neutral map insertion: no creature target, damage pose, dice or new initiative. */
@Composable
internal fun EscortEventPresentation(event: CombatEvent, onDone: () -> Unit) {
    val path = remember(event) { escortAnimationPoints(event) }
    val pauseClock = LocalPresentationPauseClock.current
    val progress = remember(event) { Animatable(0f) }
    val motion = !LocalReducedMotion.current
    var revealed by remember(event) { mutableStateOf(false) }
    val effects by rememberUpdatedState(LocalGameEffects.current)
    val soundId = LocalSoundPresentationId.current
    val after = event.escortAfter ?: event.escortBefore
    val before = event.escortBefore ?: after
    LaunchedEffect(event, motion) {
        if (!revealed) {
            if (path.size > 1 && motion) pauseClock.presentationAnimation { progress.animateTo(path.lastIndex.toFloat(), tween(path.lastIndex * 400, easing = LinearEasing)) }
            else progress.snapTo(path.lastIndex.coerceAtLeast(0).toFloat())
            revealed = true
        }
    }
    LaunchedEffect(event, revealed) {
        if (!revealed) return@LaunchedEffect
        pauseClock.presentationDelay(if (event.kind == CombatEventKind.ESCORT_EXPIRED) 1800 else 1000)
        onDone()
    }
    LaunchedEffect(event, soundId) {
        val sounds = combatSounds(event)
        if (motion) {
            var elapsed = 0L
            for (sound in sounds) {
                pauseClock.presentationDelay((sound.atMillis - elapsed).coerceAtLeast(0)); elapsed = sound.atMillis
                if (soundId != null) effects?.play(soundId, sound.phase, sound.sound)
            }
        } else if (soundId != null) sounds.lastOrNull()?.let { effects?.play(soundId, it.phase, it.sound) }
    }
    val displayedProgress = if (!motion) path.lastIndex.coerceAtLeast(0).toFloat() else progress.value
    val index = displayedProgress.toInt().coerceIn(0, path.lastIndex.coerceAtLeast(0))
    val fallback = after?.tile ?: event.actor.tile
    val from = path.getOrNull(index) ?: Offset(fallback.x.toFloat(), fallback.y.toFloat())
    val to = path.getOrNull(index + 1) ?: from
    val fraction = displayedProgress - index
    val position = from + (to - from) * fraction
    val displayed = if (event.kind == CombatEventKind.ESCORT_EXPIRED || displayedProgress >= path.lastIndex) after else before
    val battle = remember(event, displayed) { CombatDemo(fighters = event.fighters, activeId = event.actor.id, escortObjective = displayed) }
    BoxWithConstraints(Modifier.fillMaxSize().background(BattleInk).clickable(onClick = {}).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val boardWidth = ((maxHeight - 150.dp).coerceAtLeast(160.dp) * 1.2f).coerceAtMost(850.dp)
        Column(Modifier.widthIn(max = boardWidth).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (event.kind == CombatEventKind.ESCORT_EXPIRED) "Выезд закрыт" else "Прикрываем повозку", color = BattleGold,
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Battlefield(battle, escortPosition = position, highlightTurn = false)
            Text(escortOutcome(event), color = BattleMint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}
