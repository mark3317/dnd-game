package ru.markn.dnd.combat

import kotlinx.serialization.Serializable

@Serializable
enum class EscortPhase { ACTIVE, ESCAPED, EXPIRED }

/** A neutral object: no health, initiative, controller or creature identity. */
@Serializable
data class EscortObjectiveDefinition(val path: List<Tile>, val lastRound: Int = 6) {
    init {
        require(lastRound > 0)
        require(path.size >= 2 && path.distinct().size == path.size)
        require(path.all { it in CombatDemo.tiles && it !in CombatDemo.walls })
        require(path.zipWithNext().all { (a, b) -> a.distance(b) == 1 })
    }
}

@Serializable
data class EscortObjectiveState(
    val definition: EscortObjectiveDefinition,
    val step: Int = 0,
    val phase: EscortPhase = EscortPhase.ACTIVE,
) {
    init {
        require(step in definition.path.indices)
        require((phase == EscortPhase.ESCAPED) == (step == definition.path.lastIndex))
    }
    val tile: Tile get() = definition.path[step]
    val remainingMoves: Int get() = definition.path.lastIndex - step
}
