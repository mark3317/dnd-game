package ru.markn.dnd.adventure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import ru.markn.dnd.combat.*

/** Opt-in robot experiment: ordinary dice and rules, no health/position fixtures. */
class EscortBalanceProbe {
    private val turnLimit = 160
    private val format = Json { prettyPrint = true }

    private data class Run(
        val seed: Int, val outcome: String, val turns: Int, val round: Int, val maxWait: Int,
        val pushes: Int, val finalStep: Int, val finalPhase: String, val expiryRound: Int?, val invalidBlockedPushes: Int,
        val heroTurns: Map<String, Int>, val exitOverlap: Boolean, val safePathOccupied: Boolean,
        val log: List<String>,
    ) {
        fun json(trace: Boolean = false) = buildJsonObject {
            put("seed", seed); put("outcome", outcome); put("participantTurns", turns); put("round", round)
            put("maxWaitOtherTurns", maxWait); put("pushActions", pushes)
            put("finalStep", finalStep); put("finalPhase", finalPhase)
            put("expiryRound", expiryRound?.let(::JsonPrimitive) ?: JsonNull)
            put("invalidBlockedPushes", invalidBlockedPushes)
            putJsonObject("heroTurns") { heroTurns.forEach { (id, count) -> put(id, count) } }
            put("safeExitSharesHeroTile", exitOverlap); put("safeExitPathContainsStandingOrDownedHero", safePathOccupied)
            if (trace) putJsonArray("trace") { log.forEach { add(it) } }
        }
    }

    @Test fun seededEscortProbe() {
        assumeTrue("Set DND_ESCORT_BALANCE_PROBE=1 to run the opt-in escort probe",
            System.getenv("DND_ESCORT_BALANCE_PROBE") == "1")
        val seeds = (System.getenv("DND_ESCORT_BALANCE_SEEDS")?.toIntOrNull() ?: 32).also { require(it in 1..128) }
        val started = System.nanoTime()
        val cells = mutableListOf<JsonObject>()
        for (count in 2..4) for (role in CharacterClass.entries)
            for (policy in listOf("escort", "eliminate", "block_route", "hold_window")) {
                val runs = (0 until seeds).map { simulate(count, role, policy, it) }
                cells += buildJsonObject {
                    put("partySize", count); put("class", role.name); put("policy", policy); put("samples", seeds)
                    putJsonObject("outcomes") { runs.groupingBy { it.outcome }.eachCount().forEach { (outcome, n) -> put(outcome, n) } }
                    put("participantTurnsMedian", median(runs.map { it.turns }))
                    put("participantTurnsP95", runs.map { it.turns }.sorted()[ceil(seeds * .95).toInt() - 1])
                    put("maxWaitOtherTurns", runs.maxOf { it.maxWait })
                    put("meanPushActions", runs.map { it.pushes }.average())
                    put("expiryCount", runs.count { it.expiryRound != null })
                    put("invalidBlockedPushes", runs.sumOf { it.invalidBlockedPushes })
                    put("safeExitSharesHeroTile", runs.count { it.exitOverlap })
                    put("safeExitPathContainsStandingOrDownedHero", runs.count { it.safePathOccupied })
                    putJsonArray("runs") { runs.forEach { add(it.json(it.seed == 0 || it.outcome == "turn_limit" || it.exitOverlap)) } }
                }
            }
        val report = buildJsonObject {
            put("schema", 1); put("policyVersion", "visible-escort-v1"); put("kotlin", KotlinVersion.CURRENT.toString())
            put("seedStart", 0); put("seedCount", seeds); put("sampleCount", seeds * cells.size)
            put("participantTurnLimit", turnLimit); put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            put("scope", "Correct matching ReadyCharacters profiles; identical actual cart/enemies; robot choices, not human balance or presentation timing")
            put("waitingDefinition", "Consecutive other participant turns while standing; downed time reset, not wall-clock")
            put("safeExitNote", "Auto-success after elimination may narratively clear the path; occupied path/exit flags describe presentation limitations, not failed mechanics")
            putJsonArray("cells") { cells.forEach { add(it) } }
        }
        val path = Path.of("build", "reports", "escort-balance", "probe-$seeds-seeds.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, format.encodeToString(JsonObject.serializer(), report) + "\n")
        println("Escort probe: ${seeds * cells.size} battles, ${path.toAbsolutePath()}")
    }

    private fun simulate(count: Int, role: CharacterClass, policy: String, seed: Int): Run {
        val random = Random(seed)
        val dice = DiceRoller { random.nextInt(1, it + 1) }
        val scenario = ArchiveDemo.definition
        val profile = ReadyCharacters.all.single { it.characterClass == role }
        val party = List(count) { index ->
            val chosen = profile.copy(name = "${profile.name} ${index + 1}")
            assertNull(chosen.validationError())
            Player("hero-$index", "Игрок $index", chosen.hero(scenario), scenario.startLocation)
        }
        val adventure = AdventureEngine().newGame(players = party, scenarioId = scenario.id)
            .copy(scene = SceneState(setOf(FloodedArchive.INDEX_READ, ArchiveDemo.ESCORT_READY)))
        var state = adventure.createEncounter(dice)
        val waiting = party.associate { it.id to 0 }.toMutableMap()
        val heroTurns = party.associate { it.id to 0 }.toMutableMap()
        val blocker = party[if (count >= 3) 2 else 0].id
        var turns = 0
        var maxWait = 0
        var invalidBlockedPushes = 0
        var expiryRound: Int? = null
        while (!state.finished && turns < turnLimit) {
            val actor = state.activeId
            if (state.active.enemy) state = state.enemyTurn(dice)
            else {
                heroTurns[actor] = heroTurns.getValue(actor) + 1
                val cart = state.escortObjective!!
                val next = cart.definition.path.getOrNull(cart.step + 1)
                if (cart.phase == EscortPhase.ACTIVE && state.active.tile.distance(cart.tile) == 1 &&
                    next != null && state.occupant(next) != null) {
                    assertSame(state, state.execute(DemoAction.ESCORT, cart.tile, DiceRoller { error("Blocked push rolled") }))
                    invalidBlockedPushes++
                }
                state = heroTurn(state, dice, policy, blocker).endTurn()
            }
            turns++
            if (expiryRound == null && state.escortObjective!!.phase == EscortPhase.EXPIRED) expiryRound = state.round
            for (hero in state.fighters.filterNot { it.enemy }) {
                waiting[hero.id] = if (hero.id == actor || hero.hp == 0) 0 else waiting.getValue(hero.id) + 1
                maxWait = maxOf(maxWait, waiting.getValue(hero.id))
            }
            assertTrue(state.fighters.all { it.hp in 0..it.maxHp && it.tile !in CombatDemo.walls })
            val occupied = state.fighters.filter { it.hp > 0 || !it.enemy }.map { it.tile }
            assertEquals(occupied.distinct().size, occupied.size)
            if (state.escortObjective!!.phase != EscortPhase.ESCAPED) assertFalse(state.escortObjective!!.tile in occupied)
        }
        val objective = state.escortObjective!!
        val outcome = when {
            !state.finished -> "turn_limit"
            !state.victory -> "defeat"
            objective.phase == EscortPhase.ESCAPED -> "courier_safe"
            else -> "archive_defended_after_window"
        }
        val autoExit = state.events.lastOrNull { it.kind == CombatEventKind.ESCORT_MOVE && it.title == "Свободный выезд" }
        return Run(seed, outcome, turns, state.round, maxWait,
            state.events.count { it.kind == CombatEventKind.ESCORT_MOVE && it.title == "Выезд повозки" }, objective.step, objective.phase.name, expiryRound,
            invalidBlockedPushes, heroTurns,
            objective.phase == EscortPhase.ESCAPED && state.fighters.any { !it.enemy && it.tile == objective.tile },
            autoExit?.let { event -> event.path.drop(1).any { tile -> event.fighters.any { !it.enemy && it.tile == tile } } } ?: false,
            state.log)
    }

    private fun heroTurn(initial: CombatDemo, dice: DiceRoller, policy: String, blocker: String): CombatDemo {
        var state = healOrRescue(initial, dice)
        if (state.acted) return state
        val cart = state.escortObjective!!
        val activeEscort = cart.phase == EscortPhase.ACTIVE
        if (activeEscort && policy == "hold_window") return state.execute(DemoAction.GUARD, null, dice)
        if (activeEscort && policy == "block_route" && state.activeId == blocker) {
            val next = cart.definition.path[cart.step + 1]
            if (state.active.tile != next && state.occupant(next) == null) state = moveToward(state, listOf(next), dice)
            return state.execute(DemoAction.GUARD, null, dice)
        }
        if (activeEscort && policy != "eliminate") {
            if (state.preview(DemoAction.ESCORT, cart.tile).valid) return state.execute(DemoAction.ESCORT, cart.tile, dice)
            val next = cart.definition.path[cart.step + 1]
            if (state.active.tile == next) {
                val aside = CombatDemo.tiles.filter { it !in cart.definition.path && state.preview(DemoAction.MOVE, it).valid }
                    .minByOrNull { state.pathTo(it).size }
                if (aside != null) state = state.execute(DemoAction.MOVE, aside, dice)
            } else if (state.occupant(next) == null) {
                state = moveToward(state, CombatDemo.tiles.filter { it.distance(cart.tile) == 1 && it != next }, dice)
            }
            if (state.preview(DemoAction.ESCORT, cart.tile).valid) return state.execute(DemoAction.ESCORT, cart.tile, dice)
        }
        val victim = attackTarget(state)
        if (victim == null && !state.moved) {
            val enemies = state.fighters.filter { it.enemy && it.hp > 0 }
            state = moveToward(state, CombatDemo.tiles.filter { tile ->
                enemies.any { tile.distance(it.tile) <= state.active.range && CombatDemo.clearSight(tile, it.tile) }
            }, dice)
        }
        val target = attackTarget(state)
        if (target != null) {
            val skill = state.active.characterClass in setOf(DemoClass.MAGE, DemoClass.ROGUE) && state.preview(DemoAction.SKILL, target.tile).valid
            return state.execute(if (skill) DemoAction.SKILL else DemoAction.ATTACK, target.tile, dice)
        }
        return state.execute(DemoAction.GUARD, null, dice)
    }

    private fun healOrRescue(initial: CombatDemo, dice: DiceRoller): CombatDemo {
        var state = initial
        val downed = state.fighters.filter { !it.enemy && it.hp == 0 && it.id !in state.raisedHeroes }
        if (state.active.bandages > 0) {
            var target = downed.firstOrNull { state.preview(DemoAction.BANDAGE, it.tile).valid }
            if (target == null && downed.isNotEmpty()) {
                val tile = CombatDemo.tiles.filter { tile -> downed.any { tile.distance(it.tile) == 1 } && state.preview(DemoAction.MOVE, tile).valid }
                    .minByOrNull { state.pathTo(it).size }
                if (tile != null) state = state.execute(DemoAction.MOVE, tile, dice)
                target = downed.firstOrNull { state.preview(DemoAction.BANDAGE, it.tile).valid }
            }
            if (target != null) return state.execute(DemoAction.BANDAGE, target.tile, dice)
        }
        if (state.active.characterClass == DemoClass.WARRIOR && state.active.hp * 2 <= state.active.maxHp && state.preview(DemoAction.SKILL, null).valid)
            return state.execute(DemoAction.SKILL, null, dice)
        if (state.active.characterClass == DemoClass.BARD) {
            val patient = state.fighters.filter { !it.enemy && it.hp * 2 <= it.maxHp && state.preview(DemoAction.SKILL, it.tile).valid }
                .minByOrNull { it.hp.toDouble() / it.maxHp }
            if (patient != null) return state.execute(DemoAction.SKILL, patient.tile, dice)
        }
        if (state.active.hp * 2 <= state.active.maxHp && state.preview(DemoAction.BANDAGE, state.active.tile).valid)
            return state.execute(DemoAction.BANDAGE, state.active.tile, dice)
        return state
    }

    private fun attackTarget(state: CombatDemo) = state.fighters.filter { it.enemy && state.preview(DemoAction.ATTACK, it.tile).valid }.minByOrNull { it.hp }
    private fun moveToward(state: CombatDemo, targets: List<Tile>, dice: DiceRoller): CombatDemo {
        if (state.moved) return state
        val path = targets.map { state.pathTo(it, limit = CombatDemo.columns * CombatDemo.rows) }
            .filter { it.isNotEmpty() }.minByOrNull { it.size } ?: return state
        return state.execute(DemoAction.MOVE, path[minOf(3, path.lastIndex)], dice)
    }
    private fun median(values: List<Int>) = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
}
