package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.GameWireJson

class CombatInteractionTest {
    private fun battle(id: String = "bran") = CombatDemo().copy(activeId = id)

    @Test fun actionPaletteDependsOnSelectedCreatureAndClass() {
        val warrior = battle()
        assertTrue(warrior.contextActions(null).isEmpty())
        assertTrue(warrior.contextActions(Tile(5, 4)).isEmpty())
        assertEquals(listOf(DemoAction.GUARD, DemoAction.BANDAGE, DemoAction.SKILL), warrior.contextActions(warrior.active.tile))
        assertEquals(listOf(DemoAction.HELP, DemoAction.BANDAGE), warrior.contextActions(Tile(0, 3)))
        assertEquals(listOf(DemoAction.ATTACK), warrior.contextActions(Tile(4, 1)))
        val mage = battle("mira")
        assertEquals(listOf(DemoAction.ATTACK, DemoAction.SKILL), mage.contextActions(Tile(4, 1)))
        assertFalse(mage.contextActions(mage.active.tile).contains(DemoAction.SKILL))
        val bard = battle("lada")
        assertTrue(bard.contextActions(Tile(0, 3)).contains(DemoAction.SKILL))
        assertFalse(DemoAction.MOVE in warrior.contextActions(warrior.active.tile))
    }

    @Test fun onlyBothSpentResourcesEndTurnAndRejectedCommandsDoNotSpend() {
        val initial = battle()
        assertEquals(initial, initial.completeExhaustedTurn())
        assertEquals(initial.copy(acted = true), initial.copy(acted = true).completeExhaustedTurn())
        val moved = initial.execute(DemoAction.MOVE, Tile(1, 1))
        assertEquals(initial.activeId, moved.completeExhaustedTurn().activeId)
        val guarded = moved.execute(DemoAction.GUARD, null).completeExhaustedTurn()
        assertNotEquals(initial.activeId, guarded.activeId)
        assertFalse(guarded.acted)
        assertFalse(guarded.moved)
        assertEquals(initial, initial.execute(DemoAction.MOVE, Tile(2, 2)).completeExhaustedTurn())
        assertEquals(CombatEventKind.MOVE, guarded.events[0].kind)
        assertEquals(CombatEventKind.GUARD, guarded.events[1].kind)
    }

    @Test fun movementSnapshotsPreserveEveryStepAndPreActionPositions() {
        val initial = battle()
        val result = initial.execute(DemoAction.MOVE, Tile(2, 0))
        val event = result.events.single()
        assertEquals(initial.active.tile, event.path.first())
        assertEquals(Tile(2, 0), event.path.last())
        assertTrue(event.path.zipWithNext().all { (a, b) -> a.distance(b) == 1 })
        assertEquals(initial.fighters, event.fighters)
        assertEquals(initial.active, event.actor)
        assertEquals(result, GameWireJson.decodeFromString<CombatDemo>(GameWireJson.encodeToString(result)))
    }

    @Test fun initiativeSelectsOneOwnDieWithoutChangingSharedOrder() {
        val state = CombatDemo.start(DiceRoller { 12 })
        val intro = state.events.single()
        assertEquals("rin", intro.ownInitiative("rin").first.id)
        assertEquals(state.initiative.first { it.fighterId == "rin" }, intro.ownInitiative("rin").second)
        assertEquals("bran", intro.ownInitiative(null).first.id)
        assertEquals(state.initiative, intro.initiative)
    }

    @Test fun pairedEventsContainActualDiceAndHealingWithoutInventedChecks() {
        val initial = battle().let { state -> state.copy(fighters = state.fighters.map { if (it.id == "bran") it.copy(tile = Tile(3, 1)) else it }) }
        val miss = initial.execute(DemoAction.ATTACK, Tile(4, 1), DiceRoller { 1 }).events.single()
        assertFalse(miss.hit)
        assertEquals(listOf(1), miss.rolls.single().dice)
        assertEquals(miss.target!!.hp, miss.healthAfter)
        val hit = initial.execute(DemoAction.ATTACK, Tile(4, 1), DiceRoller { it }).events.single()
        assertEquals(2, hit.rolls.size)
        assertEquals(2, hit.rolls[1].dice.size)
        val wounded = battle().let { state -> state.copy(fighters = state.fighters.map { if (it.id == "bran") it.copy(hp = 2) else it }) }
        val bandage = wounded.execute(DemoAction.BANDAGE, wounded.active.tile).events.single()
        assertEquals(6, bandage.healthAfter)
        assertTrue(bandage.rolls.isEmpty())
    }

    @Test fun movementRejectsObstacleOccupationAndDistanceSpecifically() {
        val initial = battle()
        assertTrue(initial.preview(DemoAction.MOVE, Tile(2, 2)).detail.contains("преграда"))
        assertTrue(initial.preview(DemoAction.MOVE, Tile(0, 3)).detail.contains("занята"))
        assertTrue(initial.preview(DemoAction.MOVE, Tile(5, 4)).detail.contains("3 шагов"))
    }
}
