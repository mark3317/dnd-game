package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.GameWireJson

class EscortObjectiveTest {
    private val neverRoll = DiceRoller { error("A cart action must not roll dice") }
    private val definition = EscortObjectiveDefinition((0..5).map { Tile(it, 4) })
    private fun battle(step: Int = 0, round: Int = 1): CombatDemo {
        val fighters = listOf(
            DemoFighter("hero", "Герой", "Воин", "Г", Tile(step, 3), 14, 14, characterClass = DemoClass.WARRIOR),
            DemoFighter("enemy", "Враг", "Налётчик", "В", Tile(5, 0), 10, 10, enemy = true),
        )
        return CombatDemo(fighters = fighters, initiative = listOf(DemoInitiative("enemy", 20, 0), DemoInitiative("hero", 10, 0)),
            activeId = "hero", round = round, escortObjective = EscortObjectiveState(definition, step))
    }

    @Test fun cartIsNeutralAndPushCostsOnlyOneActionWithoutMovingHeroOrRolling() {
        val state = battle()
        val tile = state.escortObjective!!.tile
        assertEquals(listOf(DemoAction.ESCORT), state.contextActions(tile))
        assertNull(state.occupant(tile))
        assertTrue(state.pathTo(tile).isEmpty())
        assertFalse(state.preview(DemoAction.MOVE, tile).valid)
        assertFalse(state.preview(DemoAction.ATTACK, tile).valid)
        assertTrue(state.preview(DemoAction.ESCORT, tile).valid)
        assertFalse(state.acted)
        val pushed = state.execute(DemoAction.ESCORT, tile, neverRoll)
        assertEquals(1, pushed.escortObjective!!.step)
        assertEquals(state.fighters, pushed.fighters)
        assertEquals(state.initiative, pushed.initiative)
        assertTrue(pushed.acted)
        assertFalse(pushed.moved)
        assertTrue(pushed.diceEvents.isEmpty())
        assertEquals(listOf(tile, Tile(1, 4)), pushed.events.single().path)
        assertEquals(state.escortObjective, pushed.events.single().escortBefore)
        assertEquals(pushed.escortObjective, pushed.events.single().escortAfter)
        assertSame(pushed, pushed.execute(DemoAction.ESCORT, pushed.escortObjective!!.tile, neverRoll))
        assertEquals(pushed, GameWireJson.decodeFromString<CombatDemo>(GameWireJson.encodeToString(pushed)))
    }

    @Test fun blockedDistantOrStaleCartTargetDoesNotSpendActionAndDownedHeroReservesPath() {
        val state = battle()
        val tile = state.escortObjective!!.tile
        val blocked = state.copy(fighters = state.fighters + DemoFighter("fallen", "Союзник", "Воин", "С", Tile(1, 4), 0, 14))
        assertFalse(blocked.preview(DemoAction.ESCORT, tile).valid)
        assertTrue(blocked.preview(DemoAction.ESCORT, tile).detail.contains("Союзник"))
        assertSame(blocked, blocked.execute(DemoAction.ESCORT, tile, neverRoll))
        assertSame(state, state.execute(DemoAction.ESCORT, Tile(1, 4), neverRoll))
        val distant = state.copy(fighters = state.fighters.map { if (!it.enemy) it.copy(tile = Tile(3, 0)) else it })
        assertSame(distant, distant.execute(DemoAction.ESCORT, tile, neverRoll))
        val fallenEnemy = state.copy(fighters = state.fighters + DemoFighter("fallen", "Налётчик", "Враг", "С", Tile(1, 4), 0, 14, enemy = true))
        assertTrue(fallenEnemy.preview(DemoAction.ESCORT, tile).valid)
    }

    @Test fun exitOnLastActionOfSixthRoundWinsBeforeDeadlineAndCannotRepeat() {
        val state = battle(step = 4, round = 6)
        val result = state.execute(DemoAction.ESCORT, state.escortObjective!!.tile, neverRoll)
        assertTrue(result.finished)
        assertTrue(result.victory)
        assertEquals(EscortPhase.ESCAPED, result.escortObjective!!.phase)
        assertEquals(Tile(5, 4), result.escortObjective!!.tile)
        assertEquals(6, result.round)
        assertSame(result, result.endTurn())
        assertSame(result, result.execute(DemoAction.ESCORT, Tile(5, 4), neverRoll))
        assertEquals(1, result.events.count { it.kind == CombatEventKind.ESCORT_MOVE })
    }

    @Test fun sixthRoundWrapExpiresOnceWithoutRestartingOrRestoringResources() {
        val state = battle(round = 6).copy(spentSkills = setOf("hero"), assisted = setOf("hero"), guarded = setOf("hero"),
            raisedHeroes = setOf("hero"), fighters = battle().fighters.map { if (!it.enemy) it.copy(hp = 5, bandages = 0) else it })
        val expired = state.endTurn()
        assertEquals(7, expired.round)
        assertEquals("enemy", expired.activeId)
        assertEquals(EscortPhase.EXPIRED, expired.escortObjective!!.phase)
        assertFalse(expired.finished)
        assertEquals(state.fighters, expired.fighters)
        assertEquals(state.initiative, expired.initiative)
        assertEquals(state.spentSkills, expired.spentSkills)
        assertEquals(state.assisted, expired.assisted)
        assertEquals(state.guarded, expired.guarded)
        assertEquals(state.raisedHeroes, expired.raisedHeroes)
        val event = expired.events.single()
        assertEquals(CombatEventKind.ESCORT_EXPIRED, event.kind)
        assertEquals(EscortPhase.ACTIVE, event.escortBefore!!.phase)
        assertEquals(EscortPhase.EXPIRED, event.escortAfter!!.phase)
        val next = expired.copy(activeId = "hero").endTurn()
        assertEquals(1, next.events.count { it.kind == CombatEventKind.ESCORT_EXPIRED })
        assertFalse(next.copy(activeId = "hero").preview(DemoAction.ESCORT, next.escortObjective!!.tile).valid)
    }

    @Test fun lastEnemyDefeatSafelyCompletesActiveEscortButDoesNotReopenExpiredWindow() {
        val initial = battle().let { state -> state.copy(fighters = state.fighters.map {
            if (it.enemy) it.copy(tile = Tile(0, 2), hp = 1, armor = 1) else it
        }) }
        val won = initial.execute(DemoAction.ATTACK, Tile(0, 2), DiceRoller { 2 })
        assertTrue(won.victory)
        assertEquals(EscortPhase.ESCAPED, won.escortObjective!!.phase)
        val escape = won.events.last()
        assertEquals(CombatEventKind.ESCORT_MOVE, escape.kind)
        assertEquals(definition.path, escape.path)
        val expired = initial.copy(escortObjective = initial.escortObjective!!.copy(phase = EscortPhase.EXPIRED))
            .execute(DemoAction.ATTACK, Tile(0, 2), DiceRoller { 2 })
        assertTrue(expired.victory)
        assertEquals(EscortPhase.EXPIRED, expired.escortObjective!!.phase)
        assertEquals(0, expired.escortObjective!!.step)
        assertEquals(0, expired.events.count { it.kind == CombatEventKind.ESCORT_MOVE })
    }

    @Test fun ordinaryEventsKeepHistoricalCartSnapshotAndInvalidDefinitionsAreRejected() {
        val state = battle()
        val guarded = state.execute(DemoAction.GUARD, null, neverRoll)
        assertEquals(state.escortObjective, guarded.events.single().escortBefore)
        assertEquals(state.escortObjective, guarded.events.single().escortAfter)
        for (path in listOf(listOf(Tile(0, 4)), listOf(Tile(0, 4), Tile(2, 4)),
            listOf(Tile(2, 0), Tile(2, 1)), listOf(Tile(0, 4), Tile(1, 4), Tile(0, 4))))
            assertFailsWith<IllegalArgumentException> { EscortObjectiveDefinition(path) }
        assertFailsWith<IllegalArgumentException> { EscortObjectiveState(definition, definition.path.lastIndex) }
        assertFailsWith<IllegalArgumentException> { EscortObjectiveState(definition, 0, EscortPhase.ESCAPED) }
    }
}
