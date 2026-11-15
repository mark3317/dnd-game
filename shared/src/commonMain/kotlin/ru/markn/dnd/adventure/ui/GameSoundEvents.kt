package ru.markn.dnd.adventure.ui

import kotlinx.serialization.Serializable
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*

internal enum class GameSound(val assetId: String) {
    DICE_CAST("impact.wood.light"), DICE_LAND("impact.wood.heavy"),
    SWING("blade.slice"), IMPACT("impact.metal.light"), CLOTH("cloth.rustle"),
    STEP_A("step.stone.1"), STEP_B("step.stone.2"), CELLAR("ambience.dungeon"),
    CART_MOVE("impact.wood.light"), // Wooden movement accent, not a synthesized wheel recording.
}

@Serializable
internal data class GameSoundAsset(val id: String, val file: String, val duration_ms: Long)

internal data class TimedGameSound(val phase: String, val sound: GameSound, val atMillis: Long)

/** Uses only confirmed immutable events, never text intent or a guessed die result. */
internal fun combatSounds(event: CombatEvent): List<TimedGameSound> = when (event.kind) {
    CombatEventKind.INITIATIVE -> emptyList() // Its own visual die supplies cast/reveal cues.
    CombatEventKind.MOVE -> (0 until (event.path.size - 1).coerceAtLeast(0)).map { step ->
        TimedGameSound("step:$step", if (step % 2 == 0) GameSound.STEP_A else GameSound.STEP_B, step * 280L + 140)
    }
    CombatEventKind.ATTACK -> listOf(TimedGameSound("windup", GameSound.SWING, 550)) +
        if (event.hit) listOf(TimedGameSound("impact", GameSound.IMPACT, 1210)) else emptyList()
    CombatEventKind.HELP, CombatEventKind.HEAL, CombatEventKind.BANDAGE -> listOf(TimedGameSound("impact", GameSound.CLOTH, 1210))
    CombatEventKind.GUARD -> listOf(TimedGameSound("impact", GameSound.IMPACT, 1210))
    CombatEventKind.ESCORT_MOVE -> (0 until (event.path.size - 1).coerceAtLeast(0)).map { step ->
        TimedGameSound("cart:$step", GameSound.CART_MOVE, step * 400L + 200)
    }
    CombatEventKind.ESCORT_EXPIRED -> emptyList() // A closed road is not a hit or a failed die.
}

internal fun cellarAmbience(state: AdventureState, cinematic: Boolean): Boolean = !cinematic && state.ending == null &&
    state.combat == null && state.location == FloodedArchive.CELLAR &&
    state.scenarioId in setOf(FloodedArchive.ID, ArchiveDemo.ID)

/** Consume even inaudible phases: enabling sound never replays muted/background history. */
internal class GameSoundGate(initialHistory: Set<String>) {
    private val historical = initialHistory.toMutableSet()
    private val consumed = mutableSetOf<Pair<String, String>>()
    fun claim(eventId: String, phase: String, audible: Boolean): Boolean =
        eventId !in historical && consumed.add(eventId to phase) && audible
    fun suppress(eventIds: Set<String>) { historical += eventIds }
}
