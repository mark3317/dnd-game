package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.combat.*

class EnemyTacticsTest {
    private fun battle(enemy: Tile, hero: Tile, range: Int = 4): CombatDemo {
        val fighters = listOf(
            DemoFighter("hero", "Герой", "Воин", "Г", hero, 20, 20),
            DemoFighter("enemy", "Противник", "Налётчик", "П", enemy, 20, 20, enemy = true, range = range),
        )
        return CombatDemo(fighters = fighters, activeId = "enemy", initiative = listOf(
            DemoInitiative("enemy", 20, 0), DemoInitiative("hero", 10, 0)))
    }

    @Test fun rangedEnemyWithdrawsFromAdjacentHeroAndFiresFromReachableCover() {
        val initial = battle(Tile(4, 2), Tile(4, 3))
        val result = initial.enemyTurn(DiceRoller { 1 })
        val enemy = result.fighters.single { it.enemy }
        assertEquals(Tile(4, 1), enemy.tile)
        assertTrue(enemy.tile.distance(result.active.tile) > 1)
        assertNotNull(result.lastAttack)
        assertFalse(result.lastAttack!!.hit)
        val movement = result.events.first()
        assertEquals(CombatEventKind.MOVE, movement.kind)
        assertEquals(initial.active.tile, movement.path.first())
        assertEquals(enemy.tile, movement.path.last())
        assertTrue(movement.path.size in 2..4)
        assertTrue(movement.path.zipWithNext().all { (a, b) -> a.distance(b) == 1 })
        assertTrue(movement.path.none { it in CombatDemo.walls })
        assertEquals("hero", result.activeId)
    }

    @Test fun rangedEnemyTakesCoverAndKeepsItRatherThanApproachingIntoMelee() {
        val initial = battle(Tile(5, 1), Tile(5, 4))
        val covered = initial.enemyTurn(DiceRoller { 1 })
        assertEquals(Tile(4, 1), covered.fighters.single { it.enemy }.tile)
        assertNotNull(covered.lastAttack)
        val next = covered.copy(activeId = "enemy", events = emptyList(), moved = false, acted = false)
            .enemyTurn(DiceRoller { 1 })
        assertEquals(Tile(4, 1), next.fighters.single { it.enemy }.tile)
        assertEquals(listOf(CombatEventKind.ATTACK), next.events.map { it.kind })
    }

    @Test fun meleeEnemyClosesToAttackInsteadOfUsingRangedRetreatPolicy() {
        val initial = battle(Tile(5, 1), Tile(5, 4), range = 1)
        val result = initial.enemyTurn(DiceRoller { 1 })
        assertEquals(1, result.fighters.single { it.enemy }.tile.distance(result.active.tile))
        assertNotNull(result.lastAttack)
    }

    @Test fun rangedEnemyApproachesWhenNoReachableFiringPositionExists() {
        val initial = battle(Tile(0, 0), Tile(5, 4), range = 2)
        val result = initial.enemyTurn(DiceRoller { error("No legal shot should roll dice") })
        val enemy = result.fighters.single { it.enemy }
        assertTrue(enemy.tile.distance(result.active.tile) < initial.active.tile.distance(result.active.tile))
        assertNull(result.lastAttack)
        assertTrue(result.events.single().path.size in 2..4)
    }

    @Test fun seed28WallGeometryUsesFullRouteInsteadOfAlternatingLocallyClosestTiles() {
        // Reduced from the two-warrior greedy-policy seed 28 trace: the shooter
        // occupies D3 and a downed hero reserves E5, blocking the short approaches.
        val initial = battle(Tile(4, 3), Tile(1, 2), range = 1).let { state ->
            val fighters = state.fighters + listOf(
                DemoFighter("shooter", "Стрелок", "Налётчик", "С", Tile(3, 2), 10, 10, enemy = true, range = 4),
                DemoFighter("downed", "Павший герой", "Воин", "П", Tile(4, 4), 0, 14),
            )
            state.copy(fighters = fighters, initiative = fighters.mapIndexed { index, fighter ->
                DemoInitiative(fighter.id, 20 - index, 0)
            })
        }
        fun distanceToAttack(state: CombatDemo): Int {
            val pursuer = state.copy(activeId = "enemy")
            val hero = state.fighters.single { it.id == "hero" }
            if (pursuer.active.tile.distance(hero.tile) <= pursuer.active.range &&
                CombatDemo.clearSight(pursuer.active.tile, hero.tile)) return 0
            return CombatDemo.tiles.filter { tile ->
                tile.distance(hero.tile) <= pursuer.active.range && CombatDemo.clearSight(tile, hero.tile)
            }.map { pursuer.pathTo(it, limit = CombatDemo.columns * CombatDemo.rows) }
                .filter { it.isNotEmpty() }.minOf { it.size - 1 }
        }
        val first = initial.enemyTurn(DiceRoller { error("Attack is not yet reachable") })
        val route = first.events.single().path
        assertEquals(initial.active.tile, route.first())
        assertEquals(4, route.size)
        assertEquals(distanceToAttack(initial) - 3, distanceToAttack(first))
        assertTrue(route.none { it == Tile(3, 2) || it == Tile(4, 4) || it in CombatDemo.walls })
        assertNull(first.lastAttack)
        var next = first
        repeat(2) {
            val remaining = distanceToAttack(next)
            val eventCount = next.events.size
            next = next.copy(activeId = "enemy", moved = false, acted = false).enemyTurn(DiceRoller { 1 })
            val movement = next.events.drop(eventCount).single { it.kind == CombatEventKind.MOVE }
            assertEquals(remaining - (movement.path.size - 1), distanceToAttack(next))
        }
        assertEquals(0, distanceToAttack(next))
        assertNotNull(next.lastAttack)
        assertEquals("Герой", next.lastAttack!!.target)
        assertTrue(next.events.filter { it.kind == CombatEventKind.MOVE }.all { event ->
            event.path.size in 2..4 && event.path.zipWithNext().all { (a, b) -> a.distance(b) == 1 } &&
                event.path.none { it in CombatDemo.walls || it == Tile(3, 2) || it == Tile(4, 4) }
        })
    }

    @Test fun enemyDoesNotInventRouteThroughOccupiedExitsWhenNoAttackPositionIsReachable() {
        val initial = battle(Tile(0, 0), Tile(5, 4), range = 1).let { state ->
            val fighters = state.fighters + listOf(
                DemoFighter("block-a", "Первый", "Налётчик", "А", Tile(1, 0), 10, 10, enemy = true),
                DemoFighter("block-b", "Второй", "Налётчик", "Б", Tile(0, 1), 10, 10, enemy = true),
            )
            state.copy(fighters = fighters, initiative = fighters.mapIndexed { index, fighter ->
                DemoInitiative(fighter.id, 20 - index, 0)
            })
        }
        val result = initial.enemyTurn(DiceRoller { error("Blocked enemy must not roll") })
        assertEquals(initial.active.tile, result.fighters.single { it.id == "enemy" }.tile)
        assertTrue(result.events.isEmpty())
        assertNull(result.lastAttack)
    }
}
