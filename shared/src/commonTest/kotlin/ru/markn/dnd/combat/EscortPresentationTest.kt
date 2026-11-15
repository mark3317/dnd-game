package ru.markn.dnd.combat

import kotlin.test.*
import androidx.compose.ui.geometry.Offset
import ru.markn.dnd.adventure.ui.*

class EscortPresentationTest {
    private val definition = EscortObjectiveDefinition((0..5).map { Tile(it, 4) })
    private fun battle(step: Int = 0, phase: EscortPhase = EscortPhase.ACTIVE) = CombatDemo(activeId = "mira",
        escortObjective = EscortObjectiveState(definition, step, phase))

    @Test fun objectiveShowsRouteCostWindowAndTheActualBlockingHero() {
        val battle = battle()
        val text = assertNotNull(battle.escortPanelText())
        assertEquals("Повозка: 0/5", text.progress)
        assertEquals("Раунд 1/6", text.window)
        assertTrue(text.detail.contains("1 действие"))
        assertTrue(text.detail.contains("без броска"))
        assertTrue(assertNotNull(text.blocker).contains("B5"))
        assertTrue(text.blocker!!.contains("Рин"))
        val downed = battle.copy(fighters = battle.fighters.map { if (it.id == "rin") it.copy(hp = 0) else it })
        assertTrue(assertNotNull(downed.escortPanelText()?.blocker).contains("Рин"))
        val cleared = battle.copy(fighters = battle.fighters.map {
            when (it.id) { "rin" -> it.copy(tile = Tile(2, 4)); "guard" -> it.copy(tile = Tile(1, 4), hp = 0); else -> it }
        })
        assertNull(cleared.escortPanelText()?.blocker)
    }

    @Test fun cartSelectionIsNeutralAndNeverBecomesAnImplicitAttackOrMove() {
        val battle = battle()
        assertNull(battle.quickMapAction(Tile(0, 4)))
        assertEquals(listOf(DemoAction.ESCORT), battle.contextActions(Tile(0, 4)))
        assertEquals(DemoAction.ATTACK, battle.quickMapAction(Tile(4, 1)))
        assertEquals(DemoAction.MOVE, battle.quickMapAction(Tile(1, 3)))
        assertNull(CombatDemo().escortPanelText())
        assertEquals(DemoAction.MOVE, CombatDemo().quickMapAction(Tile(0, 4)))
    }

    @Test fun expiredSnapshotKeepsProgressAndTruthfullyChangesTheGoal() {
        val battle = battle(step = 2, phase = EscortPhase.EXPIRED).copy(round = 7)
        val text = assertNotNull(battle.escortPanelText())
        assertEquals(Tile(2, 4), battle.escortObjective?.tile)
        assertEquals("Повозка: 2/5", text.progress)
        assertEquals("Окно выезда упущено", text.window)
        assertTrue(text.title.contains("отбейте архив"))
        assertTrue(text.detail.contains("Путь копий закрыт"))
        assertFalse(battle.preview(DemoAction.ESCORT, Tile(2, 4)).valid)
        assertNull(battle.quickMapAction(Tile(2, 4)))
    }

    @Test fun escapedResultPromisesCopiesWithoutClaimingTheOriginalsWereSaved() {
        val battle = battle(step = 5, phase = EscortPhase.ESCAPED)
        val text = assertNotNull(battle.escortPanelText())
        assertEquals("Повозка: 5/5", text.progress)
        assertTrue(text.title.contains("копии обеспечены"))
        assertTrue(text.detail.contains("Оригиналы остались в архиве"))
        val event = CombatEvent(CombatEventKind.ESCORT_MOVE, battle.active, title = "Выезд", logIndex = 0,
            fighters = battle.fighters, escortAfter = battle.escortObjective)
        assertTrue(escortOutcome(event).contains("Оригиналы остались в архиве"))
    }

    @Test fun automaticDepartureAnimatesEveryConfirmedCellWithoutDiceOrExpiryImpact() {
        val before = EscortObjectiveState(definition, step = 1)
        val after = EscortObjectiveState(definition, step = 5, phase = EscortPhase.ESCAPED)
        val battle = battle()
        val event = CombatEvent(CombatEventKind.ESCORT_MOVE, battle.active, title = "Выезд", logIndex = 0,
            fighters = battle.fighters, path = definition.path.drop(1), escortBefore = before, escortAfter = after)
        assertEquals(definition.path.drop(1), escortEventPath(event))
        assertEquals(Offset(6f, 4f), escortAnimationPoints(event).last())
        assertEquals(Tile(5, 4), event.escortAfter?.tile, "The off-frame departure must not move the authoritative object")
        assertEquals(4, combatSounds(event).size)
        assertTrue(combatSounds(event).all { it.sound == GameSound.CART_MOVE })
        assertTrue(event.rolls.isEmpty())
        assertEquals(definition.path.drop(1), escortEventPath(event.copy(path = emptyList())))
        val expired = event.copy(kind = CombatEventKind.ESCORT_EXPIRED,
            escortAfter = before.copy(phase = EscortPhase.EXPIRED), path = emptyList())
        assertTrue(escortEventPath(expired).isEmpty())
        assertTrue(combatSounds(expired).isEmpty())
        assertTrue(escortOutcome(expired).contains("тот же бой"))
        assertFalse(escortOutcome(expired).contains(battle.active.name))
    }
}
