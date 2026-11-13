package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.network.GameWireJson

class ScenariosTest {
    private val engine = AdventureEngine(DiceRoller { 20 })
    private fun start(id: String) = engine.newGame(players = AdventureEngine.demoPlayers(id).take(1), scenarioId = id)
    private fun act(state: AdventureState, id: String, resolve: Boolean = true): AdventureState {
        val action = state.scenario.actions.single { it.id == id }
        val submitted = engine.submit(state, state.activePlayer.id, state.turnId, action.text)
        return if (resolve && submitted.pendingCheck != null)
            engine.roll(submitted, submitted.activePlayer.id, submitted.pendingCheck.id) else submitted
    }
    private fun propose(state: AdventureState, effects: List<SceneEffect>, item: String? = null,
                        check: ProposedCheck? = null, failure: List<SceneEffect> = emptyList()) =
        engine.submit(state, state.activePlayer.id, state.turnId, "Пробую другой способ", GameMasterDecision(null, "Намерение",
            SceneActionPlan("Другой способ", check, item, effects, failure)))

    @Test fun scenarioOwnsInitialStateAndPresentationAndSurvivesSerialization() {
        for (scenario in Scenarios.all) {
            val state = start(scenario.id)
            assertEquals(scenario.checkBudget, state.checksRemaining)
            assertEquals(scenario.startLocation, state.location)
            assertEquals(scenario.startingItems, state.activePlayer.hero.inventory)
            assertEquals(scenario.introduction, state.journal.single().text)
            assertEquals(state, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(state)))
        }
        assertFailsWith<IllegalArgumentException> { engine.newGame(scenarioId = "unknown") }
    }

    @Test fun archiveOriginalsPathUsesTheSameMoveFactCheckAndItemConsumptionEngine() {
        var state = start(FloodedArchive.ID)
        state = act(state, "OPEN_DOOR")
        assertTrue(FloodedArchive.DOOR_OPEN in state.scene.facts)
        state = act(state, "GO_CELLAR")
        assertEquals(FloodedArchive.CELLAR, state.location)
        state = act(state, "OPEN_CHEST")
        assertEquals(2, state.checksRemaining)
        state = act(state, "SAVE_DOCUMENTS")
        assertEquals(FloodedArchive.ORIGINALS_ENDING, state.ending)
        assertTrue(assertNotNull(state.outcome).success)
        assertFalse(FloodedArchive.WRAP in state.activePlayer.hero.inventory)
        assertEquals(1, state.checksRemaining)
        assertEquals(3, state.journal.count { it.roll != null })
    }

    @Test fun archiveCopiesPathHasDifferentPrerequisitesAndDoesNotRequireBasementOrEquipment() {
        var state = start(FloodedArchive.ID)
        state = act(state, "READ_INDEX")
        state = act(state, "GO_YARD")
        val noGear = state.copy(players = state.players.map { it.copy(hero = it.hero.copy(inventory = emptyList())) })
        state = act(noGear, "REQUEST_COPIES")
        assertEquals(FloodedArchive.COPIES_ENDING, state.ending)
        assertEquals(3, state.checksRemaining)
        assertFalse(FloodedArchive.DOOR_OPEN in state.scene.facts)
        assertFalse(FloodedArchive.CHEST_OPEN in state.scene.facts)
    }

    @Test fun lockedRoutesAndMissingFactsRejectFreePlansAndHideUnavailableSuggestions() {
        val state = start(FloodedArchive.ID)
        assertFalse(state.suggestions.any { it.id == "GO_CELLAR" })
        assertSame(state, propose(state, listOf(SceneEffect.Move(FloodedArchive.CELLAR))))
        val yard = act(state, "GO_YARD")
        assertSame(yard, propose(yard, listOf(SceneEffect.Fact(FloodedArchive.COPIES_PROMISED)), check = ProposedCheck(Ability.CHARISMA, 12)))
        val noDoor = act(state, "GO_CELLAR")
        assertEquals(state.location, noDoor.location)
        assertEquals(state.turnId, noDoor.turnId)
        assertNull(noDoor.pendingCheck)
    }

    @Test fun freeArchivePlanAllowsAnotherAbilityButCannotBypassPrerequisitesOrInventIds() {
        val state = start(FloodedArchive.ID)
        val opened = propose(state, listOf(SceneEffect.Fact(FloodedArchive.DOOR_OPEN)), FloodedArchive.LOCKPICKS,
            ProposedCheck(Ability.INTELLIGENCE, 11), listOf(SceneEffect.Damage(1)))
        assertNotNull(opened.pendingCheck)
        assertEquals(Ability.INTELLIGENCE, opened.pendingCheck.ability)
        val rolled = engine.roll(opened, state.activePlayer.id, opened.pendingCheck.id)
        assertTrue(FloodedArchive.DOOR_OPEN in rolled.scene.facts)
        val duplicates = listOf(SceneEffect.Transfer(FloodedArchive.WRAP, "missing"), SceneEffect.Fact(FloodedArchive.DOOR_OPEN))
        assertSame(state, propose(state, duplicates, FloodedArchive.LOCKPICKS, ProposedCheck(Ability.DEXTERITY, 10)))
        for (foreign in listOf(SceneEffect.Fact(Lighthouse.KEEPER_HELPING), SceneEffect.Move(Lighthouse.LANTERN_ROOM), SceneEffect.Fact("invented"))) {
            assertSame(state, propose(state, listOf(SceneEffect.Fact(FloodedArchive.INDEX_READ), foreign)))
        }
        assertSame(state, propose(state, listOf(SceneEffect.Fact(FloodedArchive.INDEX_READ)), Lighthouse.ROPE))
    }

    @Test fun failureAndLastCheckUseSelectedScenarioEndings() {
        val failing = AdventureEngine(DiceRoller { 1 })
        var state = start(FloodedArchive.ID).copy(checksRemaining = 1)
        val pending = act(state, "OPEN_DOOR", resolve = false)
        state = failing.roll(pending, pending.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(FloodedArchive.FLOODED_ENDING, state.ending)
        assertFalse(assertNotNull(state.outcome).success)
        assertFalse(FloodedArchive.DOOR_OPEN in state.scene.facts)
        var winner = act(act(start(FloodedArchive.ID), "READ_INDEX"), "GO_YARD").copy(checksRemaining = 1)
        winner = act(winner, "REQUEST_COPIES")
        assertEquals(0, winner.checksRemaining)
        assertEquals(FloodedArchive.COPIES_ENDING, winner.ending)
    }

    @Test fun cancellationAndForeignActionsLeaveArchiveRulesIntact() {
        val state = start(FloodedArchive.ID)
        val pending = act(state, "OPEN_DOOR", resolve = false)
        val cancelled = engine.cancelCheck(pending, state.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(state.players, cancelled.players)
        assertEquals(state.scene, cancelled.scene)
        assertEquals(4, cancelled.checksRemaining)
        assertEquals(state.turnId, cancelled.turnId)
        assertSame(state, engine.submit(state, state.activePlayer.id, state.turnId, "Подать сигнал",
            GameMasterDecision(Lighthouse.SIGNAL, "Намерение")))
    }
}
