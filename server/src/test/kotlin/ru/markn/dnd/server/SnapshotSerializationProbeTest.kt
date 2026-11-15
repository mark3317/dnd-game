package ru.markn.dnd.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.math.ceil
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.*

/** Synthetic high-density history, never a claim about battle duration, balance or live network latency. */
class SnapshotSerializationProbeTest {
    private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun fingerprint(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (source in listOf("game/src/commonMain", "server/src/main")) Files.walk(root.resolve(source)).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                digest.update(root.relativize(path).toString().toByteArray()); digest.update(0.toByte())
                digest.update(Files.readAllBytes(path))
            }
        }
        digest.update(Files.readAllBytes(root.resolve("server/src/test/kotlin/ru/markn/dnd/server/SnapshotSerializationProbeTest.kt")))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun fixture(eventCount: Int): RoomView {
        val scenario = ArchiveDemo.definition
        val players = ReadyCharacters.all.mapIndexed { index, profile ->
            Player("stress-$index", "Stress $index", profile.hero(scenario), scenario.startLocation)
        }
        val engine = AdventureEngine(DiceRoller { it })
        val initial = engine.newGame(players = players, scenarioId = scenario.id)
        val started = engine.submit(initial, initial.activePlayer.id, initial.turnId,
            scenario.actions.single { it.id == "DEFEND" }.text)
        val battle = requireNotNull(started.combat)
        val actorId = battle.activeId
        val targetId = battle.fighters.first { it.enemy }.id
        // Synthetic close formation produces one legal ATTACK template, without new game content.
        val close = battle.copy(fighters = battle.fighters.map { fighter -> when (fighter.id) {
            actorId -> fighter.copy(tile = Tile(0, 0))
            targetId -> fighter.copy(tile = Tile(1, 0))
            else -> fighter
        } })
        assertTrue(close.preview(DemoAction.ATTACK, Tile(1, 0)).valid)
        val attacked = close.execute(DemoAction.ATTACK, Tile(1, 0), DiceRoller { it })
        val template = attacked.events.last { it.kind == CombatEventKind.ATTACK }
        assertEquals(6, template.fighters.size)
        assertTrue(template.rolls.isNotEmpty())
        // Repeat the recorded data shape with unique indexes; these are NOT 500 played actions.
        val events = (0 until eventCount).map { index -> template.copy(logIndex = index,
            rolls = template.rolls.map { it.copy(logIndex = index) }) }
        val log = events.map { "Событие ${it.logIndex + 1}: ${requireNotNull(attacked.lastAttack).description}" }
        val stress = attacked.copy(events = events, diceEvents = events.flatMap { it.rolls }, log = log, lastResult = log.last())
        val journal = events.mapIndexed { index, event -> JournalEntry(1_000L + index, EntryKind.SYSTEM, log[index],
            diceEvents = event.rolls, combatEvents = listOf(event)) }.takeLast(200)
        val state = started.copy(combat = stress, journal = journal)
        return RoomView("STRESS", players.first().id,
            state.players.map { RoomMember(it, online = true, characterConfirmed = true) }, state, revision = 1,
            scenarioId = scenario.id, serverTimeMillis = 0)
    }

    private fun eventIds(room: RoomView): List<String> = room.adventure!!.journal.flatMap { entry ->
        entry.combatEvents.mapIndexed { index, _ -> "${entry.id}:combat:$index" }
    }

    private fun project(room: RoomView, trimLog: Boolean): RoomView {
        val before = room.adventure!!
        val battle = before.combat!!
        val projected = battle.copy(events = emptyList(), diceEvents = emptyList(), log = if (trimLog) battle.log.takeLast(200) else battle.log)
        val result = room.copy(adventure = before.copy(combat = projected))
        // Everything except the explicitly modeled histories must remain exactly equal.
        assertEquals(room, result.copy(adventure = result.adventure!!.copy(combat = battle)))
        assertEquals(before, result.adventure!!.copy(combat = battle))
        assertEquals(battle, projected.copy(events = battle.events, diceEvents = battle.diceEvents, log = battle.log))
        assertEquals(before.journal, result.adventure!!.journal)
        assertEquals(eventIds(room), eventIds(result))
        assertEquals(battle.finished, projected.finished)
        assertEquals(battle.victory, projected.victory)
        return result
    }

    private fun stats(values: List<Double>) = buildJsonObject {
        val sorted = values.sorted()
        put("n", sorted.size); put("min_ms", sorted.first())
        put("median_ms", (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2)
        put("p95_nearest_rank_ms", sorted[ceil(.95 * sorted.size).toInt() - 1]); put("max_ms", sorted.last())
    }

    private fun measure(room: RoomView, variant: String, eventCount: Int): JsonObject {
        val message: ServerMessage = ServerMessage.Snapshot(room)
        var encoded = GameWireJson.encodeToString(message)
        repeat(10) {
            assertEquals(message, GameWireJson.decodeFromString<ServerMessage>(encoded))
            encoded = GameWireJson.encodeToString(message)
        }
        val encode = List(50) {
            val start = System.nanoTime()
            encoded = GameWireJson.encodeToString(message)
            (System.nanoTime() - start) / 1_000_000.0
        }
        var decoded: ServerMessage? = null
        val decode = List(50) {
            val start = System.nanoTime()
            decoded = GameWireJson.decodeFromString<ServerMessage>(encoded)
            (System.nanoTime() - start) / 1_000_000.0
        }
        assertEquals(message, decoded)
        val decodedRoom = (decoded as ServerMessage.Snapshot).room
        assertEquals(room.adventure!!.journal, decodedRoom.adventure!!.journal)
        assertEquals(eventIds(room), eventIds(decodedRoom))
        val battle = room.adventure!!.combat!!
        return buildJsonObject {
            put("fixture_events", eventCount); put("variant", variant)
            put("utf8_bytes", encoded.toByteArray(Charsets.UTF_8).size); put("characters", encoded.length)
            put("combat_events_in_battle", battle.events.size); put("dice_events_in_battle", battle.diceEvents.size)
            put("combat_log_entries", battle.log.size); put("wire_journal_entries", room.adventure!!.journal.size)
            put("combat_events_in_journal", room.adventure!!.journal.sumOf { it.combatEvents.size })
            put("encode", stats(encode)); put("decode", stats(decode))
            putJsonArray("encode_samples_ms") { encode.forEach { add(it) } }
            putJsonArray("decode_samples_ms") { decode.forEach { add(it) } }
            put("round_trip_equal", true); put("current_state_and_journal_preserved", true)
            put("presented_event_ids_preserved", true)
        }
    }

    @Test fun measureFullHistoryAndExperimentalWireProjections() {
        assumeTrue("Set DND_RUN_SNAPSHOT_SERIALIZATION_PROBE=1 for the opt-in serialization probe",
            System.getenv("DND_RUN_SNAPSHOT_SERIALIZATION_PROBE") == "1")
        val root = repositoryRoot()
        val sourceHash = fingerprint(root)
        val rows = mutableListOf<JsonObject>()
        for (count in listOf(20, 100, 500)) {
            val room = fixture(count)
            rows += measure(room, "current_full_battle_history", count)
            val actual = room.copy(adventure = room.adventure!!.publicRoomSnapshot())
            assertEquals(project(room, trimLog = false), actual)
            rows += measure(actual, "actual_public_projection_keep_full_log", count)
            rows += measure(project(room, trimLog = true), "experiment_no_duplicate_events_log_tail_200", count)
        }
        val report = buildJsonObject {
            put("schema", "snapshot-serialization-probe-v1"); put("generated_at_utc", Instant.now().toString())
            put("source_sha256", sourceHash); put("protocol_version", PROTOCOL_VERSION)
            put("payload", "ServerMessage.Snapshot containing RoomView; JSON UTF8 only, no WebSocket headers/compression")
            put("fixture", "synthetic stress history: repeat one legal ATTACK template with unique logIndex; not a played/balanced fight")
            put("fighters_per_event", 6); put("journal_limit", 200); put("warmups_per_variant", 10); put("samples_per_operation", 50)
            put("java_runtime", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name"))
            put("architecture", System.getProperty("os.arch")); put("timer", "System.nanoTime monotonic")
            put("api_requests", 0); put("tts_requests", 0); put("runtime_projection_changed", true)
            put("limits", "single JVM; fixed case/variant order; JIT/GC not isolated; no TCP/UI/voice time; log-tail variant remains experimental")
            putJsonArray("results") { rows.forEach { add(it) } }
        }
        val output = root.resolve("server/build/reports/snapshot-serialization-probe.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println(buildJsonObject { put("probe", "snapshot-serialization"); put("variants", rows.size); put("api_requests", 0) })
    }
}
