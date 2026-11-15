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
