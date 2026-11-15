package ru.markn.dnd.combat

import kotlinx.serialization.Serializable
import ru.markn.dnd.adventure.DiceEvent

@Serializable
enum class CombatEventKind { INITIATIVE, MOVE, ATTACK, HEAL, HELP, BANDAGE, GUARD, ESCORT_MOVE, ESCORT_EXPIRED }

/** An immutable authority snapshot: clients can play every action in a batched update. */
@Serializable
data class CombatEvent(
    val kind: CombatEventKind,
    val actor: DemoFighter,
    val target: DemoFighter? = null,
    val title: String,
    val logIndex: Int,
    val fighters: List<DemoFighter>,
    val initiative: List<DemoInitiative> = emptyList(),
    val path: List<Tile> = emptyList(),
    val rolls: List<DiceEvent> = emptyList(),
    val hit: Boolean = true,
    val healthAfter: Int? = null,
    val escortBefore: EscortObjectiveState? = null,
    val escortAfter: EscortObjectiveState? = null,
)

/** Context controls the palette; validation and costs still come from preview(). */
fun CombatDemo.contextActions(tile: Tile?): List<DemoAction> {
    if (tile != null && tile == escortObjective?.tile) return listOf(DemoAction.ESCORT)
    val selected = tile?.let(::occupant) ?: return emptyList()
    return when {
        selected.id == activeId -> listOf(DemoAction.GUARD, DemoAction.BANDAGE) +
            if (selfSkill || active.characterClass == DemoClass.BARD) listOf(DemoAction.SKILL) else emptyList()
        selected.enemy != active.enemy -> listOf(DemoAction.ATTACK) +
            if (active.characterClass in listOf(DemoClass.MAGE, DemoClass.ROGUE)) listOf(DemoAction.SKILL) else emptyList()
        else -> listOf(DemoAction.HELP, DemoAction.BANDAGE) +
            if (active.characterClass == DemoClass.BARD) listOf(DemoAction.SKILL) else emptyList()
    }
}

fun CombatDemo.completeExhaustedTurn(): CombatDemo = if (playerTurn && acted && moved) endTurn() else this

fun CombatEvent.ownInitiative(playerId: String?): Pair<DemoFighter, DemoInitiative> {
    val own = fighters.firstOrNull { it.id == playerId && !it.enemy } ?: fighters.first { !it.enemy }
    return own to initiative.first { it.fighterId == own.id }
}
