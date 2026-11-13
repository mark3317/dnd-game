package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.network.GameWireJson

class SceneActionPlanTest {
    private val engine = AdventureEngine(DiceRoller { 10 })
    private fun plan(vararg effects: SceneEffect, check: ProposedCheck? = null, item: String? = null,
                     failure: List<SceneEffect> = emptyList()) = SceneActionPlan("Новый способ действия", check, item, effects.toList(), failure)
    private fun submit(state: AdventureState, plan: SceneActionPlan, using: AdventureEngine = engine) =
        using.submit(state, state.activePlayer.id, state.turnId, "Пробую новый способ",
            GameMasterDecision(null, "Все уже спасены — это не должно попасть в журнал!", plan))

    @Test fun ropeConsumesOneItemAndChangesBothFreeAndScriptedClimbPolicy() {
        val state = engine.newGame()
        val anchored = submit(state, plan(SceneEffect.Fact(Lighthouse.ROPE_SECURED), item = Lighthouse.ROPE))
        assertEquals(3, anchored.checksRemaining)
        assertEquals(2L, anchored.turnId.number)
        assertFalse(Lighthouse.ROPE in anchored.players.first().hero.inventory)
        assertTrue(Lighthouse.ROPE_SECURED in anchored.scene.facts)
        assertFalse(anchored.journal.any { it.text.contains("Все уже спасены") })
        val climb = plan(SceneEffect.Move(Lighthouse.LANTERN_ROOM), check = ProposedCheck(Ability.STRENGTH, 8))
        assertEquals(state, submit(state, climb))
        val prepared = submit(anchored, climb)
        assertEquals(8, prepared.pendingCheck!!.difficulty)
        assertEquals(anchored.players, prepared.players)
        val legacy = engine.submit(anchored, anchored.activePlayer.id, anchored.turnId, Lighthouse.CLIMB.text)
        assertEquals(8, legacy.pendingCheck!!.difficulty)
        assertEquals(anchored, submit(anchored, plan(SceneEffect.Fact(Lighthouse.ROPE_SECURED), item = Lighthouse.ROPE)))
    }

    @Test fun itemTransferPreservesCountAndRejectsRemoteUnknownOrDepletedTargetsAtomically() {
        val state = engine.newGame()
        val target = state.players[1].id
        val effect = SceneEffect.Transfer(Lighthouse.TOOLS, target)
        val transferred = submit(state, plan(effect))
        assertEquals(0, transferred.players[0].hero.inventory.count { it == Lighthouse.TOOLS })
        assertEquals(2, transferred.players[1].hero.inventory.count { it == Lighthouse.TOOLS })
        assertEquals(state.players.sumOf { it.hero.inventory.size }, transferred.players.sumOf { it.hero.inventory.size })
        assertEquals(state, submit(state, plan(effect, effect)))
        assertEquals(state, submit(state, plan(effect.copy(targetPlayerId = "unknown"))))
        assertEquals(state, submit(state, plan(effect.copy(targetPlayerId = state.activePlayer.id))))
        val remote = state.copy(players = state.players.map { if (it.id == target) it.copy(location = Lighthouse.LANTERN_ROOM) else it })
        assertEquals(remote, submit(remote, plan(effect)))
        val noTools = state.copy(players = transferred.players)
        assertEquals(noTools, submit(noTools, plan(effect)))
        val legacy = engine.submit(noTools.copy(players = noTools.players.map { it.copy(location = Lighthouse.LANTERN_ROOM) }),
            noTools.activePlayer.id, noTools.turnId, Lighthouse.REPAIR.text)
        assertNull(legacy.pendingCheck)
    }

    @Test fun storedBranchesRoundTripAndResolveOnlyOnceWithoutTrustingNarration() {
        val state = engine.newGame()
        val proposed = plan(SceneEffect.Move(Lighthouse.LANTERN_ROOM), check = ProposedCheck(Ability.STRENGTH, 10),
            item = Lighthouse.ROPE, failure = listOf(SceneEffect.Damage(2)))
        val pending = submit(state, proposed)
        assertEquals(state.players, pending.players)
        assertEquals(state.scene, pending.scene)
        val restored = GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(pending))
        assertEquals(pending, restored)
        val check = restored.pendingCheck!!
        assertEquals(proposed, check.plan)
        assertNull(check.action)
        assertEquals(restored, engine.roll(restored, state.players[1].id, check.id))
        val result = engine.roll(restored, state.activePlayer.id, check.id)
        assertEquals(Lighthouse.LANTERN_ROOM, result.players[0].location)
        assertEquals(state.players[0].hero.health, result.players[0].hero.health)
        assertEquals(2, result.checksRemaining)
        assertEquals(2L, result.turnId.number)
        assertEquals(result, engine.roll(result, state.activePlayer.id, check.id))
        assertFalse(result.journal.any { it.text.contains("Все уже спасены") })
    }

    @Test fun failureAppliesOnlyFrozenDamageAndCanLoseOnLastCheck() {
        val failing = AdventureEngine(DiceRoller { 1 })
        val state = failing.newGame().copy(checksRemaining = 1)
        val pending = submit(state, plan(SceneEffect.Move(Lighthouse.LANTERN_ROOM),
            check = ProposedCheck(Ability.DEXTERITY, 10), failure = listOf(SceneEffect.Damage(2))), failing)
        val result = failing.roll(pending, state.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(Lighthouse.SHORE, result.players.first().location)
        assertEquals(state.players.first().hero.health - 2, result.players.first().hero.health)
        assertEquals(Lighthouse.FERRY_LOST, result.ending)
        assertEquals(0, result.checksRemaining)
        assertEquals(state.turnId, result.turnId)
    }

    @Test fun cancelDoesNotApplyEitherBranchAndOldRollCannotResolveReplacement() {
        val state = engine.newGame()
        val proposed = plan(SceneEffect.Move(Lighthouse.LANTERN_ROOM), check = ProposedCheck(Ability.DEXTERITY, 10),
            failure = listOf(SceneEffect.Damage(2)))
        val pending = submit(state, proposed)
        val cancelled = engine.cancelCheck(pending, state.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(state.players, cancelled.players)
        assertEquals(state.turnId, cancelled.turnId)
        assertEquals(3, cancelled.checksRemaining)
        val replacement = submit(cancelled, proposed)
        assertEquals(replacement, engine.roll(replacement, state.activePlayer.id, pending.pendingCheck.id))
        assertEquals(cancelled, engine.submit(cancelled, state.players[1].id, state.turnId, "делаю", GameMasterDecision(null, "план", proposed)))
        assertEquals(cancelled, engine.submit(cancelled, state.activePlayer.id, TurnId(99, 1), "делаю", GameMasterDecision(null, "план", proposed)))
    }

    @Test fun helpChangesObjectiveDifficultyButCannotBeInventedFromUpstairsOrStacked() {
        val state = engine.newGame()
        val helped = submit(state, plan(SceneEffect.Fact(Lighthouse.KEEPER_HELPING)))
        assertTrue(Lighthouse.KEEPER_HELPING in helped.scene.facts)
        assertEquals(3, helped.checksRemaining)
        val signal = plan(SceneEffect.Fact(Lighthouse.FERRY_WARNED), check = ProposedCheck(Ability.CHARISMA, 10), item = Lighthouse.LANTERN)
        assertEquals(state, submit(state, signal))
        assertNotNull(submit(helped, signal).pendingCheck)
        assertEquals(helped, submit(helped, plan(SceneEffect.Fact(Lighthouse.KEEPER_HELPING))))
        val upstairs = state.copy(players = state.players.map { it.copy(location = Lighthouse.LANTERN_ROOM) })
        assertEquals(upstairs, submit(upstairs, plan(SceneEffect.Fact(Lighthouse.KEEPER_HELPING))))
    }

    @Test fun objectivesRequireLocationEquipmentAndCheckAndWinEvenOnLastRoll() {
        val state = engine.newGame().copy(checksRemaining = 1)
        val repair = plan(SceneEffect.Fact(Lighthouse.DRIVE_FREED), check = ProposedCheck(Ability.INTELLIGENCE, 12), item = Lighthouse.TOOLS)
        assertEquals(state, submit(state, repair))
        val upstairs = state.copy(players = state.players.map { it.copy(location = Lighthouse.LANTERN_ROOM) })
        for (invalid in listOf(repair.copy(check = null), repair.copy(usingItem = null),
            repair.copy(check = ProposedCheck(Ability.INTELLIGENCE, 8)),
            repair.copy(onSuccess = repair.onSuccess + SceneEffect.Fact(Lighthouse.FERRY_WARNED)))) {
            assertEquals(upstairs, submit(upstairs, invalid))
        }
        val pending = submit(upstairs, repair)
        val winner = AdventureEngine(DiceRoller { 20 }).roll(pending, upstairs.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(Lighthouse.LIGHTHOUSE_LIT, winner.ending)
        assertTrue(Lighthouse.DRIVE_FREED in winner.scene.facts)
        assertEquals(0, winner.checksRemaining)
        assertEquals(upstairs.turnId, winner.turnId)
    }

    @Test fun invalidFailureCannotSneakInAfterValidSuccessAndAllRejectionsLeaveStateUntouched() {
        val state = engine.newGame()
        val valid = plan(SceneEffect.Move(Lighthouse.LANTERN_ROOM), check = ProposedCheck(Ability.DEXTERITY, 10))
        for (invalid in listOf(
            valid.copy(check = ProposedCheck(Ability.DEXTERITY, 100)), valid.copy(description = " "),
            valid.copy(onSuccess = emptyList()), valid.copy(onSuccess = List(5) { SceneEffect.Move(Lighthouse.SHORE) }),
            valid.copy(onFailure = listOf(SceneEffect.Damage(4))), valid.copy(onFailure = listOf(SceneEffect.Damage(-1))),
            valid.copy(onFailure = listOf(SceneEffect.Damage(Int.MAX_VALUE), SceneEffect.Damage(Int.MAX_VALUE))),
            valid.copy(onFailure = listOf(SceneEffect.Damage(2), SceneEffect.Damage(2))),
            valid.copy(onFailure = listOf(SceneEffect.Fact(Lighthouse.FERRY_WARNED))),
            valid.copy(onSuccess = listOf(SceneEffect.Damage(1))), valid.copy(usingItem = Lighthouse.ROPE,
                onSuccess = listOf(SceneEffect.Transfer(Lighthouse.ROPE, state.players[1].id), SceneEffect.Fact(Lighthouse.ROPE_SECURED))),
        )) assertEquals(state, submit(state, invalid))
        assertEquals(state, engine.submit(state, state.activePlayer.id, state.turnId, "пробую",
            GameMasterDecision(Lighthouse.LOOK, "план", valid)))
    }
}
