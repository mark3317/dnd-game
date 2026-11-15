package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.combat.*

class RollPresentationTest {
    private fun roll(id: String) = PresentedRoll(id, DiceEvent("Проверка", "Рин", listOf(12), 20))

    @Test fun snapshotsIgnoreHistoryQueueEveryNewRollAndDoNotReplayDuplicates() {
        val history = listOf(roll("1"), roll("2"))
        val queue = RollPresentationQueue(history)
        queue.receive(history)
        assertTrue(queue.pending.isEmpty())
        queue.receive(history + listOf(roll("3"), roll("4")))
        queue.receive(history + listOf(roll("3"), roll("4"), roll("5")))
        assertEquals(listOf("3", "4", "5"), queue.pending.map { it.id })
        queue.advance("3")
        queue.advance("3") // stale animation completion cannot dismiss the next roll
        assertEquals(listOf("4", "5"), queue.pending.map { it.id })
    }

    @Test fun attackAdvantageDamageAndHealingUseActualAuthorityDice() {
        var battle = CombatDemo().copy(activeId = "rin")
        battle = battle.copy(fighters = battle.fighters.map { if (it.id == "rin") it.copy(tile = Tile(3, 1)) else it })
        val values = mutableListOf(1, 20, 2, 3, 4, 5)
        val sides = mutableListOf<Int>()
        val result = battle.execute(DemoAction.SKILL, Tile(4, 1), DiceRoller { size ->
            sides += size; values.removeAt(0)
        })
        val events = result.diceEvents
        assertEquals(listOf(20, 20, 6, 6, 6, 6), sides)
        assertEquals(listOf(1, 20), events[0].dice)
        assertTrue(events[0].best)
        assertEquals(result.lastAttack!!.total, events[0].total)
        assertEquals(listOf(2, 3, 4, 5), events[1].dice)
        assertEquals(result.lastAttack!!.damage, events[1].total)
        assertEquals(listOf(0, 0), events.map { it.logIndex })

        val wounded = CombatDemo().let { state -> state.copy(activeId = "bran",
            fighters = state.fighters.map { if (it.id == "bran") it.copy(hp = 1) else it }) }
        val healed = wounded.execute(DemoAction.SKILL, null, DiceRoller { 7 })
        assertEquals(9, healed.diceEvents.single().total)
        assertEquals(10, healed.fighters.first { it.id == "bran" }.hp)
        assertTrue(wounded.execute(DemoAction.GUARD, null).diceEvents.isEmpty())
    }

    @Test fun initiativeContainsEveryRolledDieAndCorrectOrder() {
        val state = CombatDemo.start(DiceRoller { 10 })
        assertEquals(state.initiative.size, state.diceEvents.size)
        state.initiative.zip(state.diceEvents).forEach { (initiative, event) ->
            assertEquals(initiative.total, event.total)
            assertEquals(10, event.dice.single())
        }
    }

    @Test fun ordinaryCheckUsesThresholdEvenOnNaturalOneOrTwenty() {
        val initial = AdventureEngine().newGame()
        val check = RollResult(CheckId(initial.sessionId, 1), initial.activePlayer.id, Ability.STRENGTH, 20, -2, 22)
        val state = initial.copy(journal = initial.journal + JournalEntry(90, EntryKind.CHECK, "Проверка", check))
        val event = requireNotNull(state.presentedRolls().single().event)
        assertEquals(false, event.success)
        assertEquals(18, event.total)
    }

    @Test fun combatProjectionShowsOneIntroAndOneInteractionInsteadOfEachCombatDie() {
        val battle = CombatDemo.start(DiceRoller { 10 })
        val initial = AdventureEngine().newGame()
        val journal = battle.log.mapIndexed { index, message -> JournalEntry(index.toLong(), EntryKind.SYSTEM, message,
            diceEvents = battle.diceEvents.filter { it.logIndex == index },
            combatEvents = battle.events.filter { it.logIndex == index }) }
        val presentations = initial.copy(journal = journal).presentedRolls()
        assertEquals(1, presentations.size)
        assertEquals(CombatEventKind.INITIATIVE, presentations.single().combat!!.kind)
        assertNull(presentations.single().event)

        val ready = CombatDemo().let { state -> state.copy(activeId = "bran",
            fighters = state.fighters.map { if (it.id == "bran") it.copy(tile = Tile(3, 1)) else it }) }
        val attack = ready.execute(DemoAction.ATTACK, Tile(4, 1), DiceRoller { it })
        val snapshot = initial.copy(journal = listOf(JournalEntry(100, EntryKind.SYSTEM, "Атака",
            diceEvents = attack.diceEvents, combatEvents = attack.events)))
        assertEquals(1, snapshot.presentedRolls().size)
        assertEquals(2, snapshot.presentedRolls().single().combat!!.rolls.size)
    }
}
