package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.combat.*

class GameSoundEventsTest {
    @Test fun confirmedHitAndMissHaveDifferentImpactPlansWithoutRollingAgain() {
        val ready = CombatDemo().let { battle -> battle.copy(activeId = "bran",
            fighters = battle.fighters.map { if (it.id == "bran") it.copy(tile = Tile(3, 1)) else it }) }
        val hit = ready.execute(DemoAction.ATTACK, Tile(4, 1), DiceRoller { it }).events.single()
        val miss = ready.execute(DemoAction.ATTACK, Tile(4, 1), DiceRoller { 1 }).events.single()
        assertTrue(hit.hit)
        assertFalse(miss.hit)
        assertEquals(listOf("windup", "impact"), combatSounds(hit).map { it.phase })
        assertEquals(listOf("windup"), combatSounds(miss).map { it.phase })
        assertEquals(listOf(550L, 1210L), combatSounds(hit).map { it.atMillis })
    }

    @Test fun movementUsesExactlyConfirmedPathStepsAndAlternatesRecordedVariants() {
        val ready = CombatDemo().copy(activeId = "bran")
        val moved = ready.execute(DemoAction.MOVE, Tile(2, 3))
        val event = moved.events.single()
        val sounds = combatSounds(event)
        assertEquals(event.path.size - 1, sounds.size)
        assertEquals(listOf(140L, 420L, 700L).take(sounds.size), sounds.map { it.atMillis })
        assertEquals(listOf(GameSound.STEP_A, GameSound.STEP_B, GameSound.STEP_A).take(sounds.size), sounds.map { it.sound })
        assertTrue(combatSounds(event.copy(path = emptyList())).isEmpty())
    }

    @Test fun initiativeDoesNotDuplicateItsNestedVisualDieAndSupportIsNotAnAttack() {
        val initiative = CombatDemo.start(DiceRoller { 10 }).events.single()
        assertTrue(combatSounds(initiative).isEmpty())
        val wounded = CombatDemo().let { battle -> battle.copy(activeId = "bran",
            fighters = battle.fighters.map { if (it.id == "bran") it.copy(hp = 1) else it }) }
        val healed = wounded.execute(DemoAction.SKILL, null, DiceRoller { 6 }).events.single()
        assertEquals(listOf(GameSound.CLOTH), combatSounds(healed).map { it.sound })
    }

    @Test fun historicalSnapshotsMutedPhasesAndRecompositionsNeverReplaySound() {
        val gate = GameSoundGate(setOf("old", "old-combat"))
        assertFalse(gate.claim("old", "dice:land", true))
        assertTrue(gate.claim("fresh", "dice:cast", true))
        assertFalse(gate.claim("fresh", "dice:cast", true))
        assertTrue(gate.claim("fresh", "dice:land", true))
        // A phase encountered while muted, disconnected or backgrounded is consumed.
        assertFalse(gate.claim("muted", "impact", false))
        assertFalse(gate.claim("muted", "impact", true))
        assertTrue(gate.claim("interrupted", "windup", true))
        gate.suppress(setOf("interrupted", "received-in-background"))
        assertFalse(gate.claim("interrupted", "impact", true))
        assertFalse(gate.claim("received-in-background", "dice:cast", true))
        val reconnected = GameSoundGate(setOf("old", "fresh", "muted"))
        assertFalse(reconnected.claim("fresh", "dice:land", true))
        assertTrue(reconnected.claim("after-reconnect", "impact", true))
    }

    @Test fun cellarAmbienceNeverLeaksIntoCourtyardCombatCinematicsOrLighthouse() {
        val archive = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID)
        val cellar = archive.copy(players = archive.players.map { it.copy(location = FloodedArchive.CELLAR) })
        assertFalse(cellarAmbience(archive, false))
        assertTrue(cellarAmbience(cellar, false))
        assertFalse(cellarAmbience(cellar, true))
        assertFalse(cellarAmbience(cellar.copy(combat = CombatDemo.start()), false))
        assertFalse(cellarAmbience(cellar.copy(ending = FloodedArchive.ORIGINALS_ENDING), false))
        assertFalse(cellarAmbience(cellar.copy(scenarioId = Lighthouse.ID), false))
    }
}
