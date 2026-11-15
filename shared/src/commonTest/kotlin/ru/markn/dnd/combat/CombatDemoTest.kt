package ru.markn.dnd.combat

import kotlin.random.Random
import kotlin.test.*
import ru.markn.dnd.adventure.DiceRoller

class CombatDemoTest {
    private class Dice(vararg val results: Int) : DiceRoller {
        val sides = mutableListOf<Int>()
        override fun roll(sides: Int): Int {
            this.sides += sides
            return results.getOrElse(this.sides.lastIndex) { error("Unexpected d$sides") }
        }
    }

    private fun battle(actor: String = "bran"): CombatDemo {
        val base = CombatDemo()
        return base.copy(activeId = actor,
            initiative = base.fighters.mapIndexed { index, fighter -> DemoInitiative(fighter.id, 20 - index, 0) })
    }
    private fun ready(actor: String = "bran"): CombatDemo {
        val base = battle(actor)
        return base.copy(fighters = base.fighters.map { if (it.id == actor) it.copy(tile = Tile(3, 1)) else it })
    }
    private fun CombatDemo.hp(id: String) = fighters.first { it.id == id }.hp

    @Test fun initiativeRollsOnceSortsAllParticipantsAndResolvesTiesStably() {
        val dice = Dice(12, 11, 10, 11, 12, 11)
        val state = CombatDemo.start(dice)
        assertEquals(List(6) { 20 }, dice.sides)
        assertEquals(listOf("rin", "mira", "lada", "raider", "bran", "guard"), state.initiative.map { it.fighterId })
        assertTrue(state.initiative.all { it.total == 12 })
        assertEquals("rin", state.activeId)
        assertEquals(6, state.log.count { "инициатива" in it })
    }

    @Test fun movementFindsWalkableRouteAndRejectsOccupiedOrBlockedTiles() {
        val state = battle()
        assertTrue(state.pathTo(Tile(3, 1)).isEmpty())
        val route = state.pathTo(Tile(2, 0))
        assertEquals(4, route.size)
        assertTrue(route.zipWithNext().all { (a, b) -> a.distance(b) == 1 })
        assertTrue(route.none { it in CombatDemo.walls })
        assertTrue(state.pathTo(state.active.tile).isEmpty())
        assertTrue(state.pathTo(Tile(2, 2)).isEmpty())
    }

    @Test fun previewIsFreeAndMovementCannotBeRepeated() {
        val state = battle()
        assertTrue(state.preview(DemoAction.MOVE, Tile(1, 1)).valid)
        assertFalse(state.moved)
        val moved = state.execute(DemoAction.MOVE, Tile(1, 1), Dice())
        assertTrue(moved.moved)
        assertFalse(moved.acted)
        assertEquals(moved, moved.execute(DemoAction.MOVE, Tile(0, 1), Dice()))
    }

    @Test fun invalidAttackDoesNotRollOrSpendActionAndWallBlocksRangedAttack() {
        val state = battle()
        assertEquals(state, state.execute(DemoAction.ATTACK, Tile(4, 1), Dice()))
        val mira = battle("mira")
        assertFalse(mira.preview(DemoAction.ATTACK, Tile(4, 1)).valid)
        assertFalse(CombatDemo.clearSight(Tile(0, 2), Tile(4, 2)))
    }

    @Test fun equalityHitsAndDamageUsesDicePlusModifierExactlyOnce() {
        val dice = Dice(9, 5) // 9 + 4 = AC 13. Adjacent attacks ignore the prototype cover tile.
        val result = ready().execute(DemoAction.ATTACK, Tile(4, 1), dice)
        assertEquals(listOf(20, 8), dice.sides)
        assertEquals(11, result.hp("guard"))
        assertTrue(result.acted)
        assertEquals(7, result.lastAttack!!.damage)
        assertEquals(result, result.execute(DemoAction.ATTACK, Tile(4, 1), Dice()))
    }

    @Test fun missSpendsActionAndDoesNotRollDamage() {
        val dice = Dice(8)
        val result = ready().execute(DemoAction.ATTACK, Tile(4, 1), dice)
        assertEquals(listOf(20), dice.sides)
        assertTrue(result.acted)
        assertFalse(result.lastAttack!!.hit)
        assertEquals(18, result.hp("guard"))
    }

    @Test fun naturalOneAlwaysMissesAndTwentyAlwaysHits() {
        assertFalse(CombatDemo.hits(1, 100, 1))
        assertTrue(CombatDemo.hits(20, -100, 100))
        val initial = ready().let { state -> state.copy(fighters = state.fighters.map { if (it.enemy) it.copy(armor = 100) else it }) }
        val result = initial.execute(DemoAction.ATTACK, Tile(4, 1), Dice(20, 8, 7))
        assertTrue(result.lastAttack!!.critical)
        assertEquals(17, result.lastAttack!!.damage) // 2d8 + 2, not 2*(d8+2).
        assertEquals(1, result.hp("guard"))
    }

    @Test fun previewChanceMatchesEnumeratedDiceIncludingNaturalExceptions() {
        assertEquals(0.95, CombatDemo.hitChance(100, 1, false), 1e-9)
        assertEquals(0.05, CombatDemo.hitChance(-100, 100, false), 1e-9)
        assertEquals(0.9975, CombatDemo.hitChance(100, 1, true), 1e-9)
        val state = ready("rin")
        val preview = state.preview(DemoAction.SKILL, Tile(4, 1))
        val wins = (1..20).sumOf { a -> (1..20).count { b -> CombatDemo.hits(maxOf(a, b), 4, 13) } }
        assertEquals(wins / 400.0, preview.chance!!, 1e-9)
        assertFalse(state.acted)
        assertTrue(state.spentSkills.isEmpty())
    }

    @Test fun rogueUsesBestOfTwoDiceAndExtraDamageButConsumesSkillOnMiss() {
        val dice = Dice(2, 18, 3, 4)
        val result = ready("rin").execute(DemoAction.SKILL, Tile(4, 1), dice)
        assertEquals(listOf(20, 20, 6, 6), dice.sides)
        assertEquals(18, result.lastAttack!!.die)
        assertEquals(9, result.lastAttack!!.damage)
        assertTrue("rin" in result.spentSkills)
        val miss = ready("rin").execute(DemoAction.SKILL, Tile(4, 1), Dice(1, 1))
        assertFalse(miss.lastAttack!!.hit)
        assertTrue("rin" in miss.spentSkills)
        assertFalse(miss.copy(acted = false).preview(DemoAction.SKILL, Tile(4, 1)).valid)
    }

    @Test fun mageCriticalDoublesAllDamageDiceAndDoesNotAddWeaponBonus() {
        val dice = Dice(20, 1, 2, 3, 4)
        val result = ready("mira").execute(DemoAction.SKILL, Tile(4, 1), dice)
        assertEquals(listOf(20, 6, 6, 6, 6), dice.sides)
        assertEquals(10, result.lastAttack!!.damage)
        assertEquals(0, result.lastAttack!!.damageBonus)
    }

    @Test fun secondWindTargetsSelfHealsWithDieAndClampsToMaximum() {
        val base = battle()
        assertFalse(base.preview(DemoAction.SKILL, null).valid)
        assertEquals(base, base.execute(DemoAction.SKILL, null, Dice()))
        val injured = base.copy(fighters = base.fighters.map { if (it.id == "bran") it.copy(hp = 8) else it })
        val dice = Dice(10)
        val result = injured.execute(DemoAction.SKILL, null, dice)
        assertEquals(listOf(10), dice.sides)
        assertEquals(14, result.hp("bran"))
        assertTrue(result.acted)
        assertTrue("bran" in result.spentSkills)
    }

    @Test fun bardHealsAllyButCannotHealEnemyOrAcrossWallOrRange() {
        val state = battle("lada")
        assertTrue(state.preview(DemoAction.SKILL, Tile(0, 3)).valid)
        val dice = Dice(6)
        val result = state.execute(DemoAction.SKILL, Tile(0, 3), dice)
        assertEquals(listOf(6), dice.sides)
        assertEquals(12, result.hp("mira"))
        assertTrue("lada" in result.spentSkills)
        assertFalse(state.preview(DemoAction.SKILL, Tile(4, 1)).valid)
        val blocked = state.copy(fighters = state.fighters.map { if (it.id == "mira") it.copy(tile = Tile(3, 1)) else it })
        assertFalse(blocked.preview(DemoAction.SKILL, Tile(3, 1)).valid)
        val far = state.copy(fighters = state.fighters.map { if (it.id == "mira") it.copy(tile = Tile(5, 4)) else it })
        assertFalse(far.preview(DemoAction.SKILL, Tile(5, 4)).valid)
    }

    @Test fun helpDoesNotHealAndAdvantageIsConsumedByNextAttackWithoutStacking() {
        val base = ready()
        val state = base.copy(fighters = base.fighters.map { if (it.id == "rin") it.copy(tile = Tile(3, 0)) else it })
        val helped = state.execute(DemoAction.HELP, Tile(3, 0), Dice())
        assertEquals(state.hp("rin"), helped.hp("rin"))
        assertTrue("rin" in helped.assisted)
        assertFalse(helped.copy(acted = false).preview(DemoAction.HELP, Tile(3, 0)).valid)
        val rinTurn = helped.copy(activeId = "rin", acted = false)
        val dice = Dice(1, 1)
        val result = rinTurn.execute(DemoAction.ATTACK, Tile(4, 1), dice)
        assertEquals(listOf(20, 20), dice.sides)
        assertFalse(result.lastAttack!!.hit)
        assertFalse("rin" in result.assisted)
        val skillDice = Dice(1, 1)
        rinTurn.execute(DemoAction.SKILL, Tile(4, 1), skillDice)
        assertEquals(listOf(20, 20), skillDice.sides) // Help + rogue advantage still only two d20.
    }

    @Test fun coverAddsArmorAtRangeAndGuardExpiresAtNextOwnTurn() {
        val state = ready()
        val enemy = state.fighters.first { it.id == "guard" }
        assertEquals(13, state.armorOf(enemy, Tile(3, 1)))
        assertEquals(15, state.armorOf(enemy, Tile(3, 0)))
        var result = state.execute(DemoAction.GUARD, null, Dice())
        assertEquals(17, result.armorOf(result.active, Tile(3, 0)))
        result = result.endTurn()
        assertTrue("bran" in result.guarded)
        while (result.activeId != "bran") result = if (result.active.enemy) result.enemyTurn(Dice(1)) else result.endTurn()
        assertEquals(2, result.round)
        assertFalse(result.acted)
        assertFalse(result.moved)
        assertFalse("bran" in result.guarded)
        assertEquals(state.initiative, result.initiative)
    }

    @Test fun deadParticipantsAreSkippedAndWrapStartsNextRound() {
        val base = battle("raider")
        val state = base.copy(fighters = base.fighters.map { if (it.id in setOf("bran", "guard")) it.copy(hp = 0) else it })
        val result = state.enemyTurn(Dice(1))
        assertEquals("mira", result.activeId)
        assertEquals(2, result.round)
    }

    @Test fun enemyMovesLegallyAndCannotAttackThroughWallsOrAcrossMap() {
        val base = battle()
        val fighters = base.fighters.filter { it.id in setOf("bran", "guard") }.map {
            if (it.id == "bran") it.copy(tile = Tile(0, 4)) else it.copy(tile = Tile(5, 0))
        }
        val state = base.copy(fighters = fighters, activeId = "guard", initiative = listOf(DemoInitiative("guard", 20, 0), DemoInitiative("bran", 10, 0)))
        assertEquals(state, state.execute(DemoAction.ATTACK, Tile(0, 4), Dice()))
        assertEquals(state, state.endTurn()) // Player cannot skip an enemy's roll.
        val result = state.enemyTurn(Dice())
        val destination = result.fighters.first { it.enemy }.tile
        assertTrue(state.pathTo(destination).size in 2..4)
        assertEquals(14, result.hp("bran"))
        assertNull(result.lastAttack)
        assertEquals("bran", result.activeId)
    }

    @Test fun enemyUsesAttackAndDamageDiceAndCanMiss() {
        val base = battle("guard")
        val state = base.copy(fighters = base.fighters.map { if (it.id == "guard") it.copy(tile = Tile(1, 1)) else it })
        val miss = state.enemyTurn(Dice(1))
        assertFalse(miss.lastAttack!!.hit)
        val hit = state.enemyTurn(Dice(20, 2, 3))
        assertTrue(hit.lastAttack!!.critical)
        assertEquals(6, hit.lastAttack!!.damage)
    }

    @Test fun victoryBlocksCommandsAndInvalidDiceDoesNotProduceNewState() {
        val base = battle()
        val state = base.copy(fighters = base.fighters.map { if (it.enemy) it.copy(hp = 0) else it })
        assertTrue(state.finished)
        assertTrue(state.victory)
        assertEquals(state, state.endTurn())
        assertEquals(state, state.execute(DemoAction.GUARD, null, Dice()))
        assertEquals(state, state.enemyTurn(Dice()))
        assertFailsWith<IllegalArgumentException> { ready().execute(DemoAction.ATTACK, Tile(4, 1), Dice(21)) }
        assertFailsWith<IllegalArgumentException> { ready().execute(DemoAction.ATTACK, Tile(4, 1), Dice(20, 9)) }
    }

    @Test fun bandagePreviewIsFreeAndSelfHealingSpendsOneItemAndActionWithoutDice() {
        val base = battle()
        val state = base.copy(fighters = base.fighters.map { if (it.id == "bran") it.copy(hp = 8) else it })
        assertTrue(state.preview(DemoAction.BANDAGE, state.active.tile).valid)
        assertTrue("8 → 12/14" in state.preview(DemoAction.BANDAGE, state.active.tile).detail)
        assertEquals(1, state.active.bandages)
        assertFalse(state.acted)
        val result = state.execute(DemoAction.BANDAGE, state.active.tile, Dice())
        assertEquals(12, result.hp("bran"))
        assertEquals(0, result.active.bandages)
        assertTrue(result.acted)
        assertFalse(result.moved)
        assertTrue(result.spentSkills.isEmpty())
        assertTrue(result.raisedHeroes.isEmpty())
        assertEquals(result, result.execute(DemoAction.BANDAGE, result.active.tile, Dice()))
        assertEquals(result.copy(acted = false), result.copy(acted = false).execute(DemoAction.BANDAGE, result.active.tile, Dice()))
    }

    @Test fun bandageClampsHealingAndRejectsFullHealthEnemiesAndDistantAlliesWithoutSpending() {
        val state = battle()
        assertEquals(state, state.execute(DemoAction.BANDAGE, state.active.tile, Dice()))
        assertEquals(state, state.execute(DemoAction.BANDAGE, Tile(4, 1), Dice()))
        assertEquals(state, state.execute(DemoAction.BANDAGE, Tile(0, 3), Dice()))
        assertEquals(state, state.execute(DemoAction.BANDAGE, null, Dice()))
        val injured = state.copy(fighters = state.fighters.map { if (it.id == "bran") it.copy(hp = 13) else it })
        assertEquals(14, injured.execute(DemoAction.BANDAGE, injured.active.tile, Dice()).hp("bran"))
    }

    @Test fun allyBandageRaisesOnceAndReservesDownedTileWithoutUsingTheirItem() {
        val base = battle()
        val state = base.copy(fighters = base.fighters.map { if (it.id == "mira") it.copy(tile = Tile(1, 3), hp = 0) else it })
        assertEquals("mira", state.occupant(Tile(1, 3))?.id)
        assertTrue(state.pathTo(Tile(1, 3)).isEmpty())
        assertFalse(state.preview(DemoAction.HELP, Tile(1, 3)).valid)
        val result = state.execute(DemoAction.BANDAGE, Tile(1, 3), Dice())
        assertEquals(4, result.hp("mira"))
        assertEquals(0, result.active.bandages)
        assertEquals(1, result.fighters.first { it.id == "mira" }.bandages)
        assertEquals(setOf("mira"), result.raisedHeroes)
        assertEquals("Уже поднимали в этом бою", result.recoveryStatus(result.fighters.first { it.id == "mira" }))
        // Healing a standing hero is still allowed after their one rescue.
        val nextHelper = result.copy(activeId = "mira", acted = false)
        assertEquals(8, nextHelper.execute(DemoAction.BANDAGE, Tile(1, 3), Dice()).hp("mira"))
    }

    @Test fun secondKnockoutRejectsAnotherHelpersBandageAndBardSkillAndSurvivesTurnAdvance() {
        val base = battle()
        val state = base.copy(fighters = base.fighters.map { if (it.id == "mira") it.copy(tile = Tile(1, 3), hp = 0) else it })
            .execute(DemoAction.BANDAGE, Tile(1, 3), Dice())
        // A real enemy hit knocks the rescued hero out again.
        val enemy = state.copy(activeId = "guard", fighters = state.fighters.map {
            when (it.id) {
                "guard" -> it.copy(tile = Tile(2, 3))
                "bran" -> it.copy(tile = Tile(0, 0))
                else -> it
            }
        }).enemyTurn(Dice(20, 4, 4))
        assertEquals(0, enemy.hp("mira"))
        assertEquals(setOf("mira"), enemy.raisedHeroes)
        val helper = enemy.copy(activeId = "rin", acted = false)
        val preview = helper.preview(DemoAction.BANDAGE, Tile(1, 3))
        assertFalse(preview.valid)
        assertTrue("уже поднимали" in preview.detail)
        assertEquals(helper, helper.execute(DemoAction.BANDAGE, Tile(1, 3), Dice()))
        val bard = helper.copy(activeId = "lada")
        assertEquals(bard, bard.execute(DemoAction.SKILL, Tile(1, 3), Dice()))
        assertEquals(setOf("mira"), helper.endTurn().raisedHeroes)
        val downedTurn = helper.copy(activeId = "mira")
        assertFalse(downedTurn.playerTurn)
        assertEquals(downedTurn, downedTurn.execute(DemoAction.BANDAGE, Tile(1, 3), Dice()))
    }

    @Test fun winningAttackRecoversAllHeroesToMinimumFourOnlyOnceWithoutRestocking() {
        val base = ready()
        val state = base.copy(raisedHeroes = setOf("mira"), fighters = base.fighters.map {
            when (it.id) {
                "guard" -> it.copy(hp = 1)
                "raider", "mira" -> it.copy(hp = 0)
                "rin" -> it.copy(hp = 1)
                "lada" -> it.copy(hp = 7)
                "bran" -> it.copy(bandages = 0)
                else -> it
            }
        })
        val result = state.execute(DemoAction.ATTACK, Tile(4, 1), Dice(9, 1))
        assertTrue(result.finished)
        assertTrue(result.victory)
        assertEquals(listOf(14, 4, 4, 7), result.fighters.filter { !it.enemy }.map { it.hp })
        assertEquals(state.fighters.map { it.bandages }, result.fighters.map { it.bandages })
        assertEquals(setOf("mira"), result.raisedHeroes)
        assertTrue("Победа" in result.lastResult)
        assertEquals(1, result.log.count { "Победа" in it })
        assertEquals(result, result.endTurn())
        assertEquals(result, result.execute(DemoAction.BANDAGE, result.active.tile, Dice()))
        assertEquals(result, result.execute(DemoAction.ATTACK, Tile(4, 1), Dice()))
    }

    @Test fun defeatDoesNotRecoverHeroesAndNewSceneResetsSuppliesAndRescueLimit() {
        val base = battle("guard")
        val state = base.copy(raisedHeroes = setOf("bran"), fighters = base.fighters.map {
            when {
                it.id == "guard" -> it.copy(tile = Tile(1, 1))
                !it.enemy -> it.copy(hp = if (it.id == "bran") 1 else 0, bandages = 0)
                else -> it
            }
        })
        val result = state.enemyTurn(Dice(20, 1, 1))
        assertTrue(result.finished)
        assertFalse(result.victory)
        assertTrue(result.fighters.filter { !it.enemy }.all { it.hp == 0 && it.bandages == 0 })
        val fresh = CombatDemo.start(Dice(10, 10, 10, 10, 10, 10))
        assertTrue(fresh.raisedHeroes.isEmpty())
        assertTrue(fresh.fighters.filter { !it.enemy }.all { it.bandages == 1 })
        assertTrue(fresh.fighters.filter { it.enemy }.all { it.bandages == 0 })
    }

    @Test fun seededFullBattleFinishesWithLegalPositionsAndHealthBounds() {
        val random = Random(312)
        val dice = DiceRoller { random.nextInt(1, it + 1) }
        var state = CombatDemo.start(dice)
        var turns = 0
        while (!state.finished && turns++ < 200) {
            if (state.active.enemy) state = state.enemyTurn(dice)
            else {
                val victim = state.fighters.firstOrNull { it.enemy && state.preview(DemoAction.ATTACK, it.tile).valid }
                state = if (victim != null) state.execute(DemoAction.ATTACK, victim.tile, dice) else {
                    val tile = CombatDemo.tiles.filter { state.preview(DemoAction.MOVE, it).valid }
                        .minByOrNull { tile -> state.fighters.filter { it.enemy && it.hp > 0 }.minOf { it.tile.distance(tile) } }
                    if (tile == null) state else state.execute(DemoAction.MOVE, tile, dice)
                }
                val afterMove = state.fighters.firstOrNull { it.enemy && state.preview(DemoAction.ATTACK, it.tile).valid }
                if (afterMove != null) state = state.execute(DemoAction.ATTACK, afterMove.tile, dice)
                state = state.endTurn()
            }
            assertTrue(state.fighters.all { it.hp in 0..it.maxHp && it.tile !in CombatDemo.walls })
            val living = state.fighters.filter { it.hp > 0 }
            assertEquals(living.size, living.map { it.tile }.distinct().size)
        }
        assertTrue(state.finished, "Full battle did not finish after $turns turns")
        assertTrue(turns > 6)
    }
}
