package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.GameWireJson

class ArchiveDemoTest {
    private var high = false
    private val engine = AdventureEngine(DiceRoller { if (high) it else 1 })
    private fun start(count: Int = 3, role: CharacterClass? = null): AdventureState {
        val scenario = ArchiveDemo.definition
        val party = List(count) { index ->
            val profile = ReadyCharacters.all[index % 3].copy(name = "Герой $index",
                characterClass = role ?: ReadyCharacters.all[index % 3].characterClass)
            Player("p$index", "Игрок $index", profile.hero(scenario), scenario.startLocation)
        }
        return engine.newGame(players = party, scenarioId = scenario.id)
    }
    private fun act(initial: AdventureState, actionId: String, player: String = "p0", success: Boolean = true): AdventureState {
        var state = initial
        while (state.activePlayer.id != player) state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        val action = state.scenario.actions.single { it.id == actionId }
        assertTrue(state.suggestions.any { it.id == actionId }, "$actionId unavailable at ${state.location}")
        state = engine.submit(state, player, state.turnId, action.text)
        val check = state.pendingCheck
        if (check != null) {
            high = success
            state = engine.roll(state, player, check.id)
            high = false
        }
        return state
    }

    private fun win(initial: AdventureState): AdventureState {
        var state = initial
        repeat(250) {
            val battle = state.combat ?: return state
            val enemies = battle.fighters.filter { it.enemy && it.hp > 0 }
            val attack = enemies.firstOrNull { battle.preview(DemoAction.ATTACK, it.tile).valid }
            high = true
            state = when {
                attack != null -> engine.combatCommand(state, battle.activeId, state.turnId, DemoAction.ATTACK, attack.tile)
                !battle.moved && !battle.acted -> {
                    val destination = CombatDemo.tiles.filter { battle.preview(DemoAction.MOVE, it).valid }
                        .minByOrNull { tile -> enemies.minOf { tile.distance(it.tile) } }
                    if (destination != null) engine.combatCommand(state, battle.activeId, state.turnId, DemoAction.MOVE, destination)
                    else { high = false; engine.endTurn(state, battle.activeId, state.turnId) }
                }
                else -> { high = false; engine.endTurn(state, battle.activeId, state.turnId) }
            }
            high = false
        }
        error("Combat did not finish")
    }

    @Test fun completeOriginalsRouteUsesChosenPartyCombatAndFinale() {
        var state = act(start(), "READ_INDEX")
        state = act(state, "STUDY_LOCKS")
        state = act(state, "FIX_DRAIN")
        val profiles = state.players.map { it.hero.profile }
        val budget = state.checksRemaining
        state = act(state, "DEFEND")
        val battle = assertNotNull(state.combat)
        assertEquals(state.players.map { it.id }.toSet(), battle.fighters.filterNot { it.enemy }.map { it.id }.toSet())
        assertEquals(state, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(state)))
        state = win(state)
        assertNull(state.combat)
        val recorded = state.journal.flatMap { it.diceEvents }
        assertTrue(recorded.any { it.title == "Инициатива" })
        assertTrue(recorded.any { it.title.startsWith("Урон") })
        val interactions = state.journal.flatMap { it.combatEvents }
        assertEquals(1, interactions.count { it.kind == CombatEventKind.INITIATIVE })
        assertTrue(interactions.any { it.kind == CombatEventKind.MOVE })
        assertTrue(interactions.any { it.kind == CombatEventKind.ATTACK && it.healthAfter == 0 })
        assertEquals(state, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(state)))
        assertNull(state.ending)
        assertTrue(ArchiveDemo.WON in state.scene.facts)
        assertEquals(budget, state.checksRemaining)
        assertEquals(profiles, state.players.map { it.hero.profile })
        assertTrue(state.players.all { it.hero.health >= 4 && it.location == FloodedArchive.HALL })
        state = act(state, "OPEN_DOOR")
        state = act(state, "GO_CELLAR")
        state = act(state, "OPEN_CHEST")
        state = act(state, "SAVE_DOCUMENTS")
        assertEquals(FloodedArchive.ORIGINALS_ENDING, state.ending)
        assertFalse(FloodedArchive.WRAP in state.players.first().hero.inventory)
    }

    private fun focusFirst(initial: AdventureState): AdventureState {
        var state = initial
        while (state.activePlayer.id != "p0") state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        return state
    }

    private fun withoutFirstHeroesLockpicks(): AdventureState {
        val won = focusFirst(win(act(start(), "DEFEND")))
        val transfer = SceneActionPlan("Передать отмычки товарищу", null, null,
            listOf(SceneEffect.Transfer(FloodedArchive.LOCKPICKS, "p1")), emptyList())
        val moved = engine.submit(won, "p0", won.turnId, "Передаю отмычки товарищу", GameMasterDecision(null, "Передача", transfer))
        assertFalse(FloodedArchive.LOCKPICKS in moved.players.first().hero.inventory)
        assertEquals(won.players.sumOf { it.hero.inventory.count { item -> item == FloodedArchive.LOCKPICKS } },
            moved.players.sumOf { it.hero.inventory.count { item -> item == FloodedArchive.LOCKPICKS } })
        return focusFirst(moved)
    }

    @Test fun originalsCanBeSavedByForceWithoutLockpicksWhileOldLockpickingRemainsAvailableToOthers() {
        var state = withoutFirstHeroesLockpicks()
        val budget = state.checksRemaining
        assertFalse(state.suggestions.any { it.id == "OPEN_DOOR" })
        assertTrue(state.suggestions.any { it.id == "FORCE_DOOR" })
        state = act(state, "FORCE_DOOR")
        assertTrue(FloodedArchive.DOOR_OPEN in state.scene.facts)
        state = act(state, "GO_CELLAR")
        state = act(state, "FORCE_CHEST")
        assertTrue(FloodedArchive.CHEST_OPEN in state.scene.facts)
        state = act(state, "SAVE_DOCUMENTS")
        assertEquals(FloodedArchive.ORIGINALS_ENDING, state.ending)
        assertEquals(budget - 3, state.checksRemaining)
        assertFalse(FloodedArchive.LOCKPICKS in state.players.first().hero.inventory)
    }

    @Test fun forceFailurePaysDamageAndOneCheckButCancelBeforeRollPaysNothing() {
        val state = withoutFirstHeroesLockpicks()
        val action = state.scenario.actions.single { it.id == "FORCE_DOOR" }
        val pending = engine.submit(state, "p0", state.turnId, action.text)
        val check = assertNotNull(pending.pendingCheck)
        assertEquals(14, check.difficulty)
        assertEquals(Ability.STRENGTH, check.ability)
        val cancelled = engine.cancelCheck(pending, "p0", check.id)
        assertEquals(state.players, cancelled.players)
        assertEquals(state.checksRemaining, cancelled.checksRemaining)
        val failed = act(state, "FORCE_DOOR", success = false)
        assertFalse(FloodedArchive.DOOR_OPEN in failed.scene.facts)
        assertEquals(state.players.first().hero.health - 2, failed.players.first().hero.health)
        assertEquals(state.checksRemaining - 1, failed.checksRemaining)
        assertNull(failed.pendingCheck)
    }

    @Test fun freeForcePlansCannotBorrowCheapLockpickingOrRemoveTheFailureRisk() {
        val state = withoutFirstHeroesLockpicks()
        val action = state.scenario.actions.single { it.id == "FORCE_DOOR" }
        val valid = assertNotNull(action.planFor(state))
        assertNull(SceneRules.validate(state, valid))
        val cheaper = valid.copy(check = assertNotNull(valid.check).copy(difficulty = 10))
        val otherAbility = valid.copy(check = valid.check!!.copy(ability = Ability.DEXTERITY, domains = emptySet()))
        val noRisk = valid.copy(onFailure = emptyList())
        for (invalid in listOf(cheaper, otherAbility, noRisk)) {
            assertNotNull(SceneRules.validate(state, invalid))
            assertSame(state, engine.submit(state, "p0", state.turnId, "Выбиваю дверь своим способом",
                GameMasterDecision(null, "Намерение", invalid)))
        }
        val studied = focusFirst(act(state, "STUDY_LOCKS"))
        assertEquals(12, assertNotNull(action.planFor(studied)?.check).difficulty)
        assertNull(SceneRules.validate(studied, assertNotNull(action.planFor(studied))))
        val encoded = GameWireJson.encodeToString(studied.scenario)
        assertEquals(studied.scenario, GameWireJson.decodeFromString<ScenarioDefinition>(encoded))
        assertTrue(GameWireJson.decodeFromString<FactDefinition>("{\"description\":\"Старый факт\"}").alternativeRequirements.isEmpty())
    }

    @Test fun scoutingSpendsOneCheckAndGivesEveryHeroOnlyTheirFirstAttackAdvantage() {
        var initial = act(start(), "GO_YARD")
        while (initial.activePlayer.id != "p0") initial = engine.endTurn(initial, initial.activePlayer.id, initial.turnId)
        val scout = initial.scenario.actions.single { it.id == "SCOUT" }
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, scout.text)
        assertEquals(initial.checksRemaining, pending.checksRemaining)
        assertTrue(assertNotNull(pending.pendingCheck).description.contains("преимущество на первую атаку"))
        assertFalse(pending.lastSpokenEntry!!.text.contains("2d20"))
        val scouted = act(initial, "SCOUT")
        assertEquals(initial.checksRemaining - 1, scouted.checksRemaining)
        assertTrue(ArchiveDemo.TRACKS in scouted.scene.facts)
        var battle = scouted.createEncounter(DiceRoller { 1 })
        assertEquals(scouted.players.map { it.id }.toSet(), battle.assisted)
        assertTrue(battle.fighters.filter { it.enemy }.none { it.id in battle.assisted })
        // Preparation is part of the same serializable combat state as ordinary help.
        assertEquals(battle, GameWireJson.decodeFromString<CombatDemo>(GameWireJson.encodeToString(battle)))
        val actor = battle.fighters.first { !it.enemy }
        val target = battle.fighters.first { it.enemy }
        battle = battle.copy(activeId = actor.id, fighters = battle.fighters.map {
            if (it.id == actor.id) it.copy(tile = Tile(5, 1)) else it
        })
        assertEquals(battle, battle.execute(DemoAction.ATTACK, null, DiceRoller { error("Invalid attack rolled dice") }))
        val rolls = mutableListOf<Int>()
        val missed = battle.execute(DemoAction.ATTACK, target.tile, DiceRoller { sides -> rolls += sides; 1 })
        assertEquals(listOf(20, 20), rolls)
        assertFalse(actor.id in missed.assisted)
        assertEquals(battle.assisted - actor.id, missed.assisted)
        assertFalse(assertNotNull(missed.lastAttack).hit)
        rolls.clear()
        missed.copy(acted = false).execute(DemoAction.ATTACK, target.tile, DiceRoller { sides -> rolls += sides; 1 })
        assertEquals(listOf(20), rolls)
    }

    @Test fun unsuccessfulOrAbsentScoutingDoesNotGrantPreparation() {
        val initial = act(start(), "GO_YARD")
        assertTrue(initial.createEncounter(DiceRoller { 1 }).assisted.isEmpty())
        val failed = act(initial, "SCOUT", success = false)
        assertEquals(initial.checksRemaining - 1, failed.checksRemaining)
        assertFalse(ArchiveDemo.TRACKS in failed.scene.facts)
        assertTrue(failed.createEncounter(DiceRoller { 1 }).assisted.isEmpty())
    }

    @Test fun scoutingIsAvailableBeforeBattleButCannotSpendChecksDuringOrAfterVictory() {
        var before = act(start(), "GO_YARD")
        while (before.activePlayer.id != "p0") before = engine.endTurn(before, before.activePlayer.id, before.turnId)
        val scout = before.scenario.actions.single { it.id == "SCOUT" }
        assertTrue(before.suggestions.any { it.id == scout.id })
        val freePlan = requireNotNull(scout.planFor(before)).copy(description = "Проследить за подходами по следам")
        assertNull(SceneRules.validate(before, freePlan))

        val fighting = act(start(), "DEFEND")
        assertEquals(fighting, engine.submit(fighting, fighting.activePlayer.id, fighting.turnId, scout.text))
        var won = act(win(fighting), "GO_YARD")
        while (won.activePlayer.id != "p0") won = engine.endTurn(won, won.activePlayer.id, won.turnId)
        assertTrue(ArchiveDemo.WON in won.scene.facts)
        assertFalse(won.suggestions.any { it.id == scout.id })
        val rejected = engine.submit(won, won.activePlayer.id, won.turnId, scout.text)
        assertNull(rejected.pendingCheck)
        assertEquals(won.checksRemaining, rejected.checksRemaining)
        assertEquals(won.scene, rejected.scene)
        assertEquals(won.players, rejected.players)
        assertEquals(won.turnId, rejected.turnId)
        assertTrue(rejected.journal.last().text.contains(won.scenario.facts.getValue(ArchiveDemo.WON).description))
        assertEquals("forbidden_facts", SceneRules.validate(won, freePlan))
        assertSame(won, engine.submit(won, won.activePlayer.id, won.turnId, "Ещё раз разведаю подходы",
            GameMasterDecision(null, "Намерение", freePlan)))
    }

    @Test fun forbiddenFactsAreValidatedAndDefaultToEmptyInOlderScenarioMetadata() {
        assertTrue(GameWireJson.decodeFromString<Requirements>("{}").forbiddenFacts.isEmpty())
        val scenario = ArchiveDemo.definition
        assertEquals(scenario, GameWireJson.decodeFromString<ScenarioDefinition>(GameWireJson.encodeToString(scenario)))
        val tracks = scenario.facts.getValue(ArchiveDemo.TRACKS)
        for (requirements in listOf(
            tracks.requirements.copy(forbiddenFacts = setOf("unknown")),
            tracks.requirements.copy(facts = setOf(ArchiveDemo.WON), forbiddenFacts = setOf(ArchiveDemo.WON)),
        )) assertFailsWith<IllegalArgumentException> {
            scenario.copy(facts = scenario.facts + (ArchiveDemo.TRACKS to tracks.copy(requirements = requirements)))
        }
    }

    @Test fun copiesRouteAndAllSameClassPartiesWorkForTwoAndFourPlayers() {
        for (count in listOf(2, 4)) for (role in CharacterClass.entries) {
            var state = act(start(count, role), "READ_INDEX")
            state = win(act(state, "DEFEND"))
            assertTrue(ArchiveDemo.WON in state.scene.facts, "$count $role")
            state = act(state, "GO_YARD")
            state = act(state, "REQUEST_COPIES")
            assertEquals(FloodedArchive.COPIES_ENDING, state.ending)
        }
    }

    @Test fun combatRejectsOtherPlayerStaleTokenTextAndForgedVictory() {
        val start = start()
        val forged = SceneActionPlan("Мгновенная победа", null, null, listOf(SceneEffect.Fact(ArchiveDemo.WON)), emptyList())
        assertEquals("combat_result_server_only", SceneRules.validate(start, forged))
        assertFalse(start.suggestions.any { it.id == "OPEN_DOOR" || it.id == "REQUEST_COPIES" })
        val state = act(start, "DEFEND")
        val battle = assertNotNull(state.combat)
        val other = state.players.first { it.id != battle.activeId }.id
        assertEquals(state, engine.combatCommand(state, other, state.turnId, DemoAction.GUARD, null))
        assertEquals(state, engine.combatCommand(state, battle.activeId, TurnId(99, 1), DemoAction.GUARD, null))
        assertEquals(state, engine.submit(state, battle.activeId, state.turnId, "Осмотреться"))
        val moved = engine.combatCommand(state, battle.activeId, state.turnId, DemoAction.GUARD, null)
        assertNotEquals(state.turnId, moved.turnId)
        assertEquals(moved, engine.combatCommand(moved, moved.activePlayer.id, state.turnId, DemoAction.GUARD, null))
    }

    @Test fun defeatTimeoutAndRestartPreserveProfilesAndResetStoryResources() {
        val initial = start(2)
        var state = act(initial, "DEFEND")
        high = true
        repeat(150) {
            if (state.combat != null) state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        }
        high = false
        assertEquals(ArchiveDemo.DEFEAT, state.ending)
        assertFalse(state.outcome!!.success)
        assertEquals(initial.players.map { it.hero.profile }, state.players.map { it.hero.profile })
        val fresh = engine.newGame(2, initial.players.map { it.copy(hero = it.hero.profile!!.hero(ArchiveDemo.definition)) }, ArchiveDemo.ID)
        assertTrue(fresh.players.all { it.hero.health == it.hero.maxHealth && it.hero.inventory.count { item -> item == ArchiveDemo.BANDAGE } == 1 })
        val timed = act(start().copy(checksRemaining = 1), "STUDY_LOCKS", success = false)
        assertEquals(FloodedArchive.FLOODED_ENDING, timed.ending)
    }

    @Test fun bandageSpendingFlowsBackToAdventureWithoutRefilling() {
        var state = act(start(), "DEFEND")
        val battle = state.combat!!
        val active = battle.active
        state = state.copy(combat = battle.copy(fighters = battle.fighters.map { if (it.id == active.id) it.copy(hp = it.maxHp - 4) else it }))
        state = engine.combatCommand(state, active.id, state.turnId, DemoAction.BANDAGE, active.tile)
        assertFalse(ArchiveDemo.BANDAGE in state.players.first { it.id == active.id }.hero.inventory)
        state = win(state)
        assertTrue(ArchiveDemo.WON in state.scene.facts)
        assertFalse(ArchiveDemo.BANDAGE in state.players.first { it.id == active.id }.hero.inventory)
    }

    @Test fun alreadyDownedHeroDoesNotBlockInitiativeAtEncounterStart() {
        val initial = start()
        val injured = initial.copy(players = initial.players.mapIndexed { index, player ->
            if (index == 0) player.copy(hero = player.hero.copy(health = 0)) else player
        })
        val state = act(injured, "DEFEND")
        assertTrue(assertNotNull(state.combat).playerTurn)
        assertNotEquals("p0", state.activePlayer.id)
        val defeated = act(initial.copy(players = initial.players.map { it.copy(hero = it.hero.copy(health = 0)) }), "DEFEND")
        assertNull(defeated.combat)
        assertEquals(ArchiveDemo.DEFEAT, defeated.ending)
    }
}
