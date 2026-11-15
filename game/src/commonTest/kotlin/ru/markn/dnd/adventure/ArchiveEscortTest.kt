package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.GameWireJson

class ArchiveEscortTest {
    private var high = false
    private val engine = AdventureEngine(DiceRoller { if (high) it else 1 })

    private fun start(count: Int = 2): AdventureState {
        val scenario = ArchiveDemo.definition
        val party = ReadyCharacters.all.take(count).mapIndexed { index, profile ->
            Player("p$index", "Игрок $index", profile.hero(scenario), scenario.startLocation)
        }
        return engine.newGame(players = party, scenarioId = scenario.id)
    }

    private fun act(initial: AdventureState, id: String, player: String = "p0"): AdventureState {
        var state = initial
        while (state.activePlayer.id != player) state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        val action = state.scenario.actions.single { it.id == id }
        return engine.submit(state, player, state.turnId, action.text)
    }

    private fun escort(count: Int = 2): AdventureState = act(act(act(start(count), "READ_INDEX"), "GO_YARD"), "ESCORT")

    @Test fun escortRequiresIndexAndExcludesOtherTriggerAndServerOutcomeForgeries() {
        val yard = act(start(), "GO_YARD").let { state -> state.copy(turnOrder = state.turnOrder.next("p0")) }
        assertFalse(yard.suggestions.any { it.id == "ESCORT" })
        val prepared = act(act(start(), "READ_INDEX"), "GO_YARD").let { state -> state.copy(turnOrder = state.turnOrder.next("p0")) }
        val action = prepared.suggestions.single { it.id == "ESCORT" }
        assertNotNull(action.detail)
        assertTrue(action.detail!!.contains("раунда 6"))
        assertNull(SceneRules.validate(prepared, requireNotNull(action.plan)))
        val definition = requireNotNull(prepared.scenario.encounter)
        assertEquals(setOf(ArchiveDemo.WON, ArchiveDemo.ESCAPED, ArchiveDemo.WINDOW_LOST), definition.serverOwnedFacts)
        for (fact in definition.serverOwnedFacts) {
            val forged = SceneActionPlan("Получить исход", null, null, listOf(SceneEffect.Fact(fact)), emptyList())
            assertEquals("combat_result_server_only", SceneRules.validate(prepared, forged))
            assertSame(prepared, engine.submit(prepared, prepared.activePlayer.id, prepared.turnId, "Мы уже победили",
                GameMasterDecision(null, "Намерение", forged)))
        }
        val combined = SceneActionPlan("Два варианта сразу", null, null,
            listOf(SceneEffect.Move(FloodedArchive.HALL), SceneEffect.Fact(ArchiveDemo.READY),
                SceneEffect.Move(FloodedArchive.YARD), SceneEffect.Fact(ArchiveDemo.ESCORT_READY)), emptyList())
        assertEquals("forbidden_facts", SceneRules.validate(prepared, combined))
    }

    @Test fun everyPartySizeStartsNeutralCartWithoutExtraInitiativeOrHero() {
        for (count in 2..4) {
            val state = escort(count)
            val battle = assertNotNull(state.combat)
            assertEquals("courier_escort", battle.encounterVariantId)
            assertEquals(Tile(0, 4), battle.escortObjective!!.tile)
            assertEquals(count + 2, battle.fighters.size)
            assertEquals(battle.fighters.map { it.id }.toSet(), battle.initiative.map { it.fighterId }.toSet())
            assertTrue(state.players.all { it.location == FloodedArchive.YARD })
            assertEquals(state, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(state)))
            assertEquals(battle.escortObjective, battle.events.first { it.kind == CombatEventKind.INITIATIVE }.escortBefore)
            assertFalse(state.journal.filter { it.isSpoken }.any { "раунда 6" in it.text || "d20" in it.text })
        }
    }

    @Test fun fiveRealPushesFinishWithHonestCourierEndingAndRejectStaleOrWrongPlayerCommand() {
        var state = escort()
        val initial = state
        val battle = requireNotNull(state.combat)
        val actor = battle.activeId
        val other = state.players.first { it.id != actor }.id
        assertSame(state, engine.combatCommand(state, other, state.turnId, DemoAction.ESCORT, battle.escortObjective!!.tile))
        assertSame(state, engine.combatCommand(state, actor, TurnId(999, 999), DemoAction.ESCORT, battle.escortObjective!!.tile))
        repeat(30) {
            val current = state.combat ?: return@repeat
            val cart = current.escortObjective!!
            if (current.preview(DemoAction.ESCORT, cart.tile).valid) {
                val before = state
                state = engine.combatCommand(state, current.activeId, state.turnId, DemoAction.ESCORT, cart.tile)
                assertNotEquals(before.turnId, state.turnId)
                assertSame(state, engine.combatCommand(state, current.activeId, before.turnId, DemoAction.ESCORT, cart.tile))
            } else if (!current.acted && !current.moved) {
                val nextTile = cart.definition.path.getOrNull(cart.step + 1)
                val destination = CombatDemo.tiles.filter { tile ->
                    tile != nextTile && tile.distance(cart.tile) == 1 && current.preview(DemoAction.MOVE, tile).valid
                }.minByOrNull { current.pathTo(it).size }
                state = if (destination != null) engine.combatCommand(state, current.activeId, state.turnId, DemoAction.MOVE, destination)
                    else engine.endTurn(state, state.activePlayer.id, state.turnId)
            } else state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        }
        assertEquals(ArchiveDemo.COURIER_SAFE, state.ending)
        assertNull(state.combat)
        assertTrue(ArchiveDemo.ESCAPED in state.scene.facts)
        assertFalse(ArchiveDemo.WON in state.scene.facts)
        assertFalse(ArchiveDemo.WINDOW_LOST in state.scene.facts)
        assertEquals(initial.checksRemaining, state.checksRemaining)
        assertEquals(5, state.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.ESCORT_MOVE })
        assertTrue(state.endingShots().isNotEmpty())
        assertFalse(state.outcome!!.description.contains("Архив защищён"))
        assertTrue(state.players.all { it.location == FloodedArchive.YARD })
    }

    @Test fun expiredWindowPreservesBattleClosesCopiesAndLeavesOriginalsAvailableAfterVictory() {
        var state = escort()
        val before = requireNotNull(state.combat)
        val actor = before.activeId
        val order = before.initiative.filterNot { it.fighterId == actor } + before.initiative.single { it.fighterId == actor }
        state = state.copy(combat = before.copy(round = 6, initiative = order, spentSkills = setOf(actor),
            raisedHeroes = setOf(actor), fighters = before.fighters.map { if (!it.enemy) it.copy(bandages = 0) else it }))
        state = engine.endTurn(state, state.activePlayer.id, state.turnId)
        val expired = requireNotNull(state.combat)
        assertEquals(EscortPhase.EXPIRED, expired.escortObjective!!.phase)
        assertEquals(order, expired.initiative)
        assertEquals(setOf(actor), expired.spentSkills)
        assertEquals(setOf(actor), expired.raisedHeroes)
        assertTrue(expired.fighters.filterNot { it.enemy }.all { it.bandages == 0 })
        assertTrue(ArchiveDemo.WINDOW_LOST in state.scene.facts)
        assertEquals(1, state.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.ESCORT_EXPIRED })
        // Finish the SAME battle: no new initiative or resources, only low-HP fixture enemies.
        state = state.copy(combat = expired.copy(fighters = expired.fighters.map { if (it.enemy) it.copy(hp = 1) else it }))
        repeat(30) {
            val current = state.combat ?: return@repeat
            val target = current.fighters.firstOrNull { it.enemy && current.preview(DemoAction.ATTACK, it.tile).valid }
            high = target != null
            state = if (target != null) engine.combatCommand(state, current.activeId, state.turnId, DemoAction.ATTACK, target.tile)
            else {
                val enemies = current.fighters.filter { it.enemy && it.hp > 0 }
                val destination = CombatDemo.tiles.filter { current.preview(DemoAction.MOVE, it).valid }
                    .minWithOrNull(compareBy<Tile> { tile ->
                        if (enemies.any { tile.distance(it.tile) <= current.active.range && CombatDemo.clearSight(tile, it.tile) }) 0 else 1
                    }.thenBy { tile -> enemies.minOf { tile.distance(it.tile) } })
                if (destination != null) engine.combatCommand(state, current.activeId, state.turnId, DemoAction.MOVE, destination)
                else engine.endTurn(state, state.activePlayer.id, state.turnId)
            }
            high = false
        }
        assertNull(state.combat)
        assertNull(state.ending)
        assertTrue(ArchiveDemo.WON in state.scene.facts)
        assertTrue(ArchiveDemo.WINDOW_LOST in state.scene.facts)
        val yard = act(state, "GO_YARD").let { it.copy(turnOrder = it.turnOrder.next("p0")) }
        assertFalse(yard.suggestions.any { it.id == "REQUEST_COPIES" || it.id == "ESCORT" })
        val copies = requireNotNull(yard.scenario.actions.single { it.id == "REQUEST_COPIES" }.planFor(yard))
        assertEquals("forbidden_facts", SceneRules.validate(yard, copies))
        val talk = engine.submit(yard, yard.activePlayer.id, yard.turnId, "Поговорить")
        assertTrue(talk.lastSpokenEntry!!.text.contains("Копии я теперь не доставлю"))
        assertTrue(state.suggestions.any { it.id == "OPEN_DOOR" })
        val hallTalk = act(state, "TALK")
        assertTrue(hallTalk.lastSpokenEntry!!.text.contains("курьер не выедет"))
        fun checked(initial: AdventureState, id: String): AdventureState {
            val pending = act(initial, id)
            val check = requireNotNull(pending.pendingCheck)
            high = true
            val result = engine.roll(pending, pending.activePlayer.id, check.id)
            high = false
            return result
        }
        var saved = checked(hallTalk, "OPEN_DOOR")
        saved = act(saved, "GO_CELLAR")
        saved = checked(saved, "OPEN_CHEST")
        saved = checked(saved, "SAVE_DOCUMENTS")
        assertEquals(FloodedArchive.ORIGINALS_ENDING, saved.ending)
        assertTrue(ArchiveDemo.WINDOW_LOST in saved.scene.facts)
        assertFalse(saved.outcome!!.description.contains("Егор везёт"))
        assertFalse(saved.endingShots().any { it.text.contains("Егор везёт") })
    }
}
