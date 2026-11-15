package ru.markn.dnd.adventure

import kotlin.test.*

class StoryCinemaTest {
    private val engine = AdventureEngine(DiceRoller { 20 })

    @Test fun openingIsOnlyOfferedBeforeTheFirstCommandIncludingCancelledChecks() {
        for (scenario in Scenarios.all) {
            val start = engine.newGame(scenarioId = scenario.id)
            assertTrue(start.isAtOpening)
            assertEquals(scenario.introduction, start.openingShots().joinToString(" ") { it.text })
            assertFalse(engine.endTurn(start, start.activePlayer.id, start.turnId).isAtOpening)
            assertFalse(start.copy(journal = start.journal + JournalEntry(2, EntryKind.SYSTEM, "Проверка отменена")).isAtOpening)
        }
    }

    @Test fun everyEndingPresentsExactlyTheAuthoredConsequencesWithoutInventingSuccess() {
        for (scenario in Scenarios.all) for ((id, outcome) in scenario.endings) {
            val ended = engine.newGame(scenarioId = scenario.id).copy(ending = id)
            assertFalse(ended.isAtOpening)
            assertEquals(outcome.description, ended.endingShots().joinToString(" ") { it.text })
            if (!outcome.success) assertFalse(ended.endingShots().any { it.art == StoryArt.DAWN })
            assertTrue(ended.storyConsequences().isEmpty())
        }
    }

    @Test fun summaryUsesOnlyConfirmedFactsAndDoesNotDependOnTruncatedJournal() {
        val start = engine.newGame(scenarioId = ArchiveDemo.ID)
        val end = start.copy(ending = FloodedArchive.COPIES_ENDING,
            scene = start.scene.copy(facts = setOf(ArchiveDemo.WON, FloodedArchive.INDEX_READ)), journal = emptyList())
        assertEquals(listOf(ArchiveDemo.WON, FloodedArchive.INDEX_READ).map { end.scenario.facts.getValue(it).description }, end.storyConsequences())
        assertTrue(end.endingShots().isNotEmpty())
        assertTrue(start.endingShots().isEmpty())
    }
}
