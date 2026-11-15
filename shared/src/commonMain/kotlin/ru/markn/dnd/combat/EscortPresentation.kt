package ru.markn.dnd.combat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class EscortPanelText(val title: String, val progress: String, val window: String,
    val detail: String, val blocker: String?)

internal fun CombatDemo.escortPanelText(): EscortPanelText? = escortObjective?.let { objective ->
    val next = objective.definition.path.getOrNull(objective.step + 1)
    EscortPanelText(
        title = when (objective.phase) {
            EscortPhase.ACTIVE -> "Прикройте выезд Егора"
            EscortPhase.ESCAPED -> "Повозка выехала · копии обеспечены"
            EscortPhase.EXPIRED -> "Выезд закрыт · отбейте архив"
        },
        progress = "Повозка: ${objective.step}/${objective.definition.path.lastIndex}",
        window = if (objective.phase == EscortPhase.ACTIVE) "Раунд $round/${objective.definition.lastRound}" else
            if (objective.phase == EscortPhase.EXPIRED) "Окно выезда упущено" else "Выезд завершён",
        detail = when (objective.phase) {
            EscortPhase.ACTIVE -> "Герой рядом → выбрать повозку → продвинуть за 1 действие, без броска. Успех обеспечит копии; оригиналы останутся в архиве."
            EscortPhase.ESCAPED -> "Егор увёз номера грамот и обеспечит копии. Оригиналы остались в архиве."
            EscortPhase.EXPIRED -> "Победите оставшихся налётчиков. Путь копий закрыт; после победы можно спасать оригиналы."
        },
        blocker = if (objective.phase == EscortPhase.ACTIVE) next?.let { cell ->
            occupant(cell)?.let { blocking -> "Путь на ${cell.label} занимает ${blocking.name}. Освободите клетку." }
        } else null,
    )
}

/** A cart double tap selects it, never sends a creature attack or a hidden move command. */
internal fun CombatDemo.quickMapAction(tile: Tile): DemoAction? {
    if (tile == escortObjective?.tile) return null
    val target = occupant(tile)
    return if (target != null && target.enemy != active.enemy) DemoAction.ATTACK else DemoAction.MOVE
}

@Composable
internal fun EscortObjectivePanel(battle: CombatDemo) {
    val text = battle.escortPanelText() ?: return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val detail = if (maxWidth >= 600.dp) text.detail else when (battle.escortObjective?.phase) {
        EscortPhase.ACTIVE -> "Рядом с повозкой → продвинуть за 1 действие."
        EscortPhase.EXPIRED -> "Отбейте архив. Путь копий закрыт."
        EscortPhase.ESCAPED -> "Копии обеспечены; оригиналы остались в архиве."
        null -> text.detail
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = BattleGold.copy(alpha = .09f), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, BattleGold.copy(alpha = .35f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.title, color = BattleGold, style = MaterialTheme.typography.titleMedium)
            Text("${text.progress} · ${text.window}", color = BattleMint, style = MaterialTheme.typography.labelMedium)
            Text(detail, color = Color(0xFFD5DFE1), style = MaterialTheme.typography.bodySmall)
            text.blocker?.let { Text(it, color = Color(0xFFF2A79C), style = MaterialTheme.typography.bodySmall) }
        }
    }
    }
}

@Composable
internal fun EscortRoute(objective: EscortObjectiveState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = if (objective.phase == EscortPhase.ACTIVE) BattleGold else Color(0xFF91A2AA)
        fun Tile.center() = Offset((x + .5f) * size.width / 6, (y + .5f) * size.height / 5)
        objective.definition.path.zipWithNext().forEach { (from, to) ->
            drawLine(color.copy(alpha = .6f), from.center(), to.center(), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())))
        }
        drawCircle(color.copy(alpha = .8f), 7.dp.toPx(), objective.definition.path.last().center(), style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun EscortCart(objective: EscortObjectiveState, selected: Boolean, modifier: Modifier = Modifier,
    onSelect: (() -> Unit)? = null) {
    val color = if (objective.phase == EscortPhase.ACTIVE) BattleGold else Color(0xFF9BACB4)
    val click = if (onSelect != null) Modifier.clickable(role = Role.Button, onClick = onSelect) else Modifier
    Column(modifier.then(click).semantics {
        contentDescription = "Повозка, ${objective.tile.label}, ${objective.step}/${objective.definition.path.lastIndex}" +
            if (objective.phase == EscortPhase.EXPIRED) ", выезд закрыт" else ""
    }.border(if (selected) 2.dp else 0.dp, if (selected) BattleGold else Color.Transparent, MaterialTheme.shapes.small)
        .background(BattleInk.copy(alpha = .48f), MaterialTheme.shapes.small), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp)) {
            val body = Offset(size.width * .15f, size.height * .22f)
            val bodySize = Size(size.width * .65f, size.height * .42f)
            drawRect(color.copy(alpha = .34f), body, bodySize)
            drawRect(color, body, bodySize, style = Stroke(2.dp.toPx()))
            drawLine(color, body, body + Offset(bodySize.width, bodySize.height), 1.dp.toPx())
            drawLine(color, body + Offset(0f, bodySize.height), body + Offset(bodySize.width, 0f), 1.dp.toPx())
            drawLine(color, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .98f, size.height * .35f), 2.dp.toPx())
            for (x in listOf(.28f, .7f)) {
                drawCircle(BattleInk, size.minDimension * .09f, Offset(size.width * x, size.height * .75f))
                drawCircle(color, size.minDimension * .09f, Offset(size.width * x, size.height * .75f), style = Stroke(2.dp.toPx()))
            }
        }
        Text("Повозка", color = color, fontSize = 10.sp, maxLines = 1)
    }
}
