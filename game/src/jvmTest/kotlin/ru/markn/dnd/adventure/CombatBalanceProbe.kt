package ru.markn.dnd.adventure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import ru.markn.dnd.combat.*

/** Opt-in evidence harness, not a default balance assertion or a model of human play. */
class CombatBalanceProbe {
    private val turnLimit = 160
    private var policy = "route"

    private data class Result(
        val seed: Int, val outcome: String, val turns: Int, val rounds: Int,
        val maxWait: Int, val heroTurns: Map<String, Int>, val heroAttacks: Int,
        val enemyAttacks: Int, val remainingHealth: Int, val spentBandages: Int,
        val log: List<String>,
    ) {
        fun json(includeTrace: Boolean = false) = buildJsonObject {
            put("seed", seed); put("outcome", outcome); put("participantTurns", turns)
            put("round", rounds); put("maxWaitOtherTurns", maxWait)
            putJsonObject("heroTurns") { heroTurns.forEach { (id, count) -> put(id, count) } }
            put("heroAttacks", heroAttacks); put("enemyAttacks", enemyAttacks)
            put("remainingHeroHealth", remainingHealth); put("spentBandages", spentBandages)
            if (includeTrace) putJsonArray("trace") { log.forEach { add(it) } }
        }
    }

    @Test fun seededPartyProbe() {
        assumeTrue("Set DND_COMBAT_BALANCE_PROBE=1 to run the opt-in combat probe",
            System.getenv("DND_COMBAT_BALANCE_PROBE") == "1")
        policy = (System.getenv("DND_COMBAT_BALANCE_POLICY") ?: "route").also {
            require(it in setOf("greedy", "route")) { "DND_COMBAT_BALANCE_POLICY must be greedy or route" }
        }
        val seeds = (System.getenv("DND_COMBAT_BALANCE_SEEDS")?.toIntOrNull() ?: 32).also {
            require(it in 1..128) { "DND_COMBAT_BALANCE_SEEDS must be 1..128" }
        }
        val started = System.nanoTime()
        val cells = mutableListOf<JsonObject>()
        for (count in 2..4) for (role in CharacterClass.entries) for (scouted in listOf(false, true)) {
            val results = (0 until seeds).map { seed -> simulate(count, role, scouted, seed) }
            cells += buildJsonObject {
                put("partySize", count); put("class", role.name); put("scouted", scouted)
                put("samples", results.size)
                put("wins", results.count { it.outcome == "victory" })
                put("losses", results.count { it.outcome == "defeat" })
                put("unfinished", results.count { it.outcome == "turn_limit" })
                put("participantTurnsMedian", median(results.map { it.turns }))
                put("participantTurnsP95", p95(results.map { it.turns }))
                put("roundMedian", median(results.map { it.rounds }))
                put("maxWaitOtherTurns", results.maxOf { it.maxWait })
                put("meanHeroAttacks", results.map { it.heroAttacks }.average())
                put("meanEnemyAttacks", results.map { it.enemyAttacks }.average())
                put("meanSpentBandages", results.map { it.spentBandages }.average())
                putJsonArray("runs") { results.forEach { add(it.json((count == 2 && role == CharacterClass.WARRIOR) || it.outcome == "turn_limit")) } }
            }
        }
        val report = buildJsonObject {
            put("schema", 1); put("policy", "visible-state-$policy-v1"); put("kotlin", KotlinVersion.CURRENT.toString())
            put("description", "Seeded robot exercise of actual combat; not human balance or duration evidence")
            put("seedStart", 0); put("seedCount", seeds); put("participantTurnLimit", turnLimit)
            put("sampleCount", seeds * cells.size)
            put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            put("waitingDefinition", "Consecutive other participant turns while hero remains standing; allies/enemies included, downed time reset, not wall-clock seconds")
            put("profiles", "Matching ReadyCharacters profile for each class, duplicated with unique IDs; original valid scores/race/background, full initial health/items")
            putJsonArray("cells") { cells.forEach { add(it) } }
        }
        val output = Path.of("build", "reports", "combat-balance", "probe-$policy-$seeds-seeds.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("Combat balance probe: ${seeds * cells.size} battles, ${output.toAbsolutePath()}")
    }

    private fun simulate(count: Int, role: CharacterClass, scouted: Boolean, seed: Int): Result {
        val random = Random(seed)
        val dice = DiceRoller { sides -> random.nextInt(1, sides + 1) }
        val scenario = ArchiveDemo.definition
        val profile = ReadyCharacters.all.single { it.characterClass == role }
        val party = List(count) { index ->
            val selected = profile.copy(name = "${profile.name} ${index + 1}")
            assertEquals(null, selected.validationError())
            Player("hero-${index + 1}", "Игрок ${index + 1}", selected.hero(scenario), scenario.startLocation)
        }
        var adventure = AdventureEngine().newGame(players = party, scenarioId = scenario.id)
        if (scouted) adventure = adventure.copy(scene = adventure.scene.copy(facts = setOf(ArchiveDemo.TRACKS)))
        var battle = adventure.createEncounter(dice)
        var turns = 0
        var maxWait = 0
        val waiting = party.associate { it.id to 0 }.toMutableMap()
        val heroTurns = party.associate { it.id to 0 }.toMutableMap()
        while (!battle.finished && turns < turnLimit) {
            val actor = battle.activeId
            if (battle.active.enemy) battle = battle.enemyTurn(dice)
            else {
                val id = battle.activeId
                heroTurns[id] = heroTurns.getValue(id) + 1
                battle = heroTurn(battle, dice).endTurn()
            }
            for (hero in battle.fighters.filterNot { it.enemy }) {
                waiting[hero.id] = if (hero.id == actor || hero.hp == 0) 0 else waiting.getValue(hero.id) + 1
                maxWait = maxOf(maxWait, waiting.getValue(hero.id))
            }
            turns++
            assertTrue(battle.fighters.all { it.hp in 0..it.maxHp && it.tile !in CombatDemo.walls })
            val occupants = battle.fighters.filter { it.hp > 0 || !it.enemy }.map { it.tile }
            assertEquals(occupants.distinct().size, occupants.size)
        }
        val attacks = battle.events.filter { it.kind == CombatEventKind.ATTACK }
        return Result(seed, when { !battle.finished -> "turn_limit"; battle.victory -> "victory"; else -> "defeat" },
            turns, battle.round, maxWait, heroTurns,
            attacks.count { !it.actor.enemy }, attacks.count { it.actor.enemy },
            battle.fighters.filterNot { it.enemy }.sumOf { it.hp },
            count - battle.fighters.filterNot { it.enemy }.sumOf { it.bandages }, battle.log)
    }

    /** Reads only the published state/previews; never sees future dice or modifies fighters. */
    private fun heroTurn(initial: CombatDemo, dice: DiceRoller): CombatDemo {
        var state = initial
        val downed = state.fighters.filter { !it.enemy && it.hp == 0 && it.id !in state.raisedHeroes }
        if (state.active.bandages > 0) {
            val nearby = downed.firstOrNull { state.preview(DemoAction.BANDAGE, it.tile).valid }
            if (nearby != null) return state.execute(DemoAction.BANDAGE, nearby.tile, dice)
            val rescue = reachable(state).filter { tile -> downed.any { tile.distance(it.tile) == 1 } }
                .minByOrNull { state.pathTo(it).size }
            if (rescue != null) {
                state = state.execute(DemoAction.MOVE, rescue, dice)
                val target = downed.first { state.preview(DemoAction.BANDAGE, it.tile).valid }
                return state.execute(DemoAction.BANDAGE, target.tile, dice)
            }
        }
        if (state.active.characterClass == DemoClass.WARRIOR && state.active.hp * 2 <= state.active.maxHp &&
            state.preview(DemoAction.SKILL, null).valid) return state.execute(DemoAction.SKILL, null, dice)
        if (state.active.characterClass == DemoClass.BARD) {
            val patient = state.fighters.filter { !it.enemy && it.hp * 2 <= it.maxHp && state.preview(DemoAction.SKILL, it.tile).valid }
                .minByOrNull { it.hp.toDouble() / it.maxHp }
            if (patient != null) return state.execute(DemoAction.SKILL, patient.tile, dice)
        }
        if (state.active.hp * 2 <= state.active.maxHp && state.preview(DemoAction.BANDAGE, state.active.tile).valid)
            return state.execute(DemoAction.BANDAGE, state.active.tile, dice)
        if (!canAttack(state)) {
            val destination = destination(state, dice)
            if (destination != null) state = state.execute(DemoAction.MOVE, destination, dice)
        }
        val targets = state.fighters.filter { it.enemy && state.preview(DemoAction.ATTACK, it.tile).valid }
        val victim = targets.minWithOrNull(compareBy<DemoFighter> { it.hp }
            .thenByDescending { state.preview(DemoAction.ATTACK, it.tile).chance ?: 0.0 })
        if (victim != null) {
            val skill = state.active.characterClass in setOf(DemoClass.MAGE, DemoClass.ROGUE) && state.preview(DemoAction.SKILL, victim.tile).valid
            return state.execute(if (skill) DemoAction.SKILL else DemoAction.ATTACK, victim.tile, dice)
        }
        return state.execute(DemoAction.GUARD, null, dice)
    }

    private fun destination(state: CombatDemo, dice: DiceRoller): Tile? {
        val enemies = state.fighters.filter { it.enemy && it.hp > 0 }
        if (policy == "greedy") return reachable(state).minWithOrNull(compareBy<Tile> { tile ->
            if (canAttack(state.execute(DemoAction.MOVE, tile, dice))) 0 else 1
        }.thenBy { tile -> enemies.minOf { tile.distance(it.tile) } }
            .thenByDescending { if (it in CombatDemo.coverTiles) 1 else 0 }
            .thenBy { state.pathTo(it).size })
        // Plan an ordinary legal route around obstacles to a firing position;
        // execute only its first three steps this turn, with the same preview guard.
        val paths = CombatDemo.tiles.filter { tile -> enemies.any {
            tile.distance(it.tile) <= state.active.range && CombatDemo.clearSight(tile, it.tile)
        } }.mapNotNull { tile ->
            state.pathTo(tile, limit = CombatDemo.columns * CombatDemo.rows).takeIf { it.isNotEmpty() }
        }
        val path = paths.minWithOrNull(compareBy<List<Tile>> { it.size }
            .thenByDescending { if (it.last() in CombatDemo.coverTiles) 1 else 0 }) ?: return null
        return path[minOf(3, path.lastIndex)].takeIf { state.preview(DemoAction.MOVE, it).valid }
    }

    private fun canAttack(state: CombatDemo) = state.fighters.any { it.enemy && state.preview(DemoAction.ATTACK, it.tile).valid }
    private fun reachable(state: CombatDemo) = CombatDemo.tiles.filter { state.preview(DemoAction.MOVE, it).valid }
    private fun median(values: List<Int>) = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
    private fun p95(values: List<Int>) = values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
}
