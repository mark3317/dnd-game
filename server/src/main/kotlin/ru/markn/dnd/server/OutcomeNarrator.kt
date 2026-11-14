package ru.markn.dnd.server

import ru.markn.dnd.adventure.*

/** Read-only storytelling after rules have committed. No action proposal can be returned. */
fun interface OutcomeNarrator {
    suspend fun narrate(outcome: OutcomeNarration): String
}

data class HeroChange(val before: Player, val after: Player)

data class ConfirmedActivity(
    val playerId: String,
    val roll: RollResult?,
    val factsAdded: Set<String>,
    val heroChanges: List<HeroChange>,
    val checksSpent: Int,
    val usingItem: String? = null,
)

data class OutcomeNarration(
    val before: AdventureState,
    val after: AdventureState,
    val activity: ConfirmedActivity,
    val history: List<ConfirmedActivity>,
) {
    val epilogue: Boolean get() = after.ending != null
}

internal fun confirmedActivity(before: AdventureState, after: AdventureState, playerId: String): ConfirmedActivity? {
    val roll = after.journal.drop(before.journal.size).mapNotNull { it.roll }.singleOrNull()
    val facts = after.scene.facts - before.scene.facts
    val changes = before.players.mapNotNull { old ->
        after.players.firstOrNull { it.id == old.id }?.takeIf { it != old }?.let { HeroChange(old, it) }
    }
    if (roll == null && facts.isEmpty() && changes.isEmpty()) return null
    return ConfirmedActivity(playerId, roll, facts, changes, before.checksRemaining - after.checksRemaining,
        if (roll != null) before.pendingCheck?.plan?.usingItem else null)
}
