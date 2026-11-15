package ru.markn.dnd.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.math.ceil
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

/** Opt-in real-loopback transport baseline; no AI, TTS, UI rendering, clock or audio simulation. */
class TcpLatencyBaselineTest {
    private data class Receipt(val elapsedMillis: Double, val room: RoomView)
    private data class Sample(
        val clients: Int, val ordinal: Int, val turn: Long, val ownerIndex: Int,
        val ownerAck: Double, val snapshotReceipts: List<Double>, val spokenReceipts: List<Double>,
        val wireJournalEntries: Int,
    ) {
        fun json() = buildJsonObject {
            put("clients", clients); put("sample", ordinal); put("turn", turn)
            put("owner_index", ownerIndex)
            put("owner_ack_ms", ownerAck)
            put("all_snapshots_ms", snapshotReceipts.max())
            put("first_spoken_journal_ms", spokenReceipts.min())
            put("owner_spoken_journal_ms", spokenReceipts[ownerIndex])
            put("all_spoken_journal_ms", spokenReceipts.max())
            putJsonArray("snapshot_receipts_ms") { snapshotReceipts.forEach { add(it) } }
            putJsonArray("spoken_journal_receipts_ms") { spokenReceipts.forEach { add(it) } }
            put("wire_journal_entries", wireJournalEntries)
        }
    }

    private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun sourceFingerprint(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (source in listOf("game/src/commonMain", "server/src/main")) Files.walk(root.resolve(source)).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                digest.update(root.relativize(path).toString().toByteArray())
                digest.update(0.toByte())
                digest.update(Files.readAllBytes(path))
            }
        }
        // Include this harness, whose observer/dispatcher overhead affects measurements.
        digest.update(Files.readAllBytes(root.resolve("server/src/test/kotlin/ru/markn/dnd/server/TcpLatencyBaselineTest.kt")))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** t0 is assigned only after all collectors are attached and immediately before command(). */
    private suspend fun measure(clients: List<NetworkGameClient>, ordinal: Int): Sample = coroutineScope {
        val before = clients.first().state.value.room!!
        val state = before.adventure!!
        val ownerIndex = clients.indexOfFirst { it.state.value.playerId == state.activePlayer.id }
        check(ownerIndex >= 0)
        val owner = clients[ownerIndex]
        val nextTurn = TurnId(state.sessionId, state.turnId.number + 1)
        val lastEntry = state.journal.last().id
        val start = AtomicLong(0)
        fun now(): Double {
            val began = start.get()
            check(began != 0L)
            return (System.nanoTime() - began) / 1_000_000.0
        }
        fun NetworkState.isNextSnapshot(): Boolean = status == ConnectionStatus.CONNECTED &&
            (room?.revision ?: 0) > before.revision && room?.adventure?.turnId == nextTurn
        val ownerAck = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) { owner.state.first { !it.busy && it.isNextSnapshot() } }.let {
                assertNull(it.error)
                Receipt(now(), it.room!!)
            }
        }
        val snapshots = clients.map { client -> async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) { client.state.first { it.isNextSnapshot() } }.let { Receipt(now(), it.room!!) }
        } }
        val spoken = clients.map { client -> async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) { client.state.first {
                it.isNextSnapshot() && it.room!!.adventure!!.journal.any { entry -> entry.id > lastEntry && entry.isSpoken }
            } }.let { Receipt(now(), it.room!!) }
        } }
        start.set(System.nanoTime())
        owner.command(NetworkAction.SUBMIT, turnId = state.turnId, text = "Осмотреться")
        val ack = ownerAck.await()
        val arrivals = snapshots.awaitAll()
        val narration = spoken.awaitAll()
        val after = ack.room.adventure!!
        assertEquals(before.revision + 1, ack.room.revision)
        assertEquals(nextTurn, after.turnId)
        assertEquals(state.checksRemaining, after.checksRemaining)
        assertEquals(state.players, after.players)
        assertEquals(state.scene, after.scene)
        assertNull(after.pendingCheck)
        assertNull(after.ending)
        assertNull(after.combat)
        assertNull(ack.room.pendingNarration)
        assertFalse(ack.room.gameMasterEnabled)
        assertNull(ack.room.presentation)
        arrivals.forEach { assertEquals(ack.room, it.room) }
        narration.forEach { assertEquals(ack.room, it.room) }
        Sample(clients.size, ordinal, nextTurn.number, ownerIndex, ack.elapsedMillis,
            arrivals.map { it.elapsedMillis }, narration.map { it.elapsedMillis }, after.journal.size)
    }

    private suspend fun cohort(count: Int): List<Sample> = coroutineScope {
        // Clients share this single runBlocking dispatcher, like a UI event loop; server uses CIO.
        val clients = List(count) { NetworkGameClient(this) }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            gameServer(Rooms(presentationEnabled = false))
        }.start()
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            var code = ""
            clients.forEachIndexed { index, client ->
                client.connect(address, "Baseline-$index", code)
                code = withTimeout(15_000) { client.state.first { it.status == ConnectionStatus.CONNECTED } }.room!!.code
            }
            withTimeout(10_000) { clients.first().state.first { it.room?.members?.size == count } }
            clients.first().command(NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID)
            withTimeout(10_000) { clients.first().state.first { !it.busy && it.room?.scenarioId == ArchiveDemo.ID } }
            confirmNetworkCharacters(*clients.toTypedArray())
            clients.first().command(NetworkAction.START)
            val started = withTimeout(10_000) { clients.first().state.first { !it.busy && it.room?.adventure != null } }.room!!
            for (client in clients) withTimeout(10_000) { client.state.first { it.room?.revision == started.revision } }
            repeat(10) { measure(clients, -1 - it) } // Warmup is real validated work, excluded from report statistics.
            (1..50).map { measure(clients, it) }
        } finally {
            clients.forEach { it.close() }
            server.stop(100, 1_000)
        }
    }

    private fun summary(samples: List<Sample>): JsonObject {
        fun stats(values: List<Double>) = buildJsonObject {
            val sorted = values.sorted()
            put("n", values.size); put("min_ms", sorted.first())
            put("median_ms", (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2)
            put("p95_nearest_rank_ms", sorted[ceil(.95 * sorted.size).toInt() - 1])
            put("max_ms", sorted.last())
        }
        return buildJsonObject {
            put("clients", samples.first().clients)
            put("owner_ack", stats(samples.map { it.ownerAck }))
            put("all_snapshots", stats(samples.map { it.snapshotReceipts.max() }))
            put("first_spoken_journal", stats(samples.map { it.spokenReceipts.min() }))
            put("owner_spoken_journal", stats(samples.map { it.spokenReceipts[it.ownerIndex] }))
            put("all_spoken_journal", stats(samples.map { it.spokenReceipts.max() }))
        }
    }

    @Test fun measureValidatedLookCommandsThroughRealTcp() = runBlocking<Unit> {
        assumeTrue("Set DND_RUN_TCP_LATENCY_BASELINE=1 to run the opt-in baseline",
            System.getenv("DND_RUN_TCP_LATENCY_BASELINE") == "1")
        val root = repositoryRoot()
        val fingerprint = sourceFingerprint(root)
        val startedAt = Instant.now().toString()
        val samples = (2..4).flatMap { cohort(it) }
        assertEquals(150, samples.size)
        val report = buildJsonObject {
            put("schema", "tcp-latency-baseline-v1"); put("started_at_utc", startedAt)
            put("source_sha256", fingerprint); put("protocol_version", PROTOCOL_VERSION)
            put("profile", "real_tcp_scripted_look_presentation_disabled")
            put("java_runtime", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name")); put("architecture", System.getProperty("os.arch"))
            put("client_dispatcher", "single shared runBlocking event loop; same process as server")
            put("clock", "System.nanoTime monotonic; production Rooms wall clock is not replaced")
            put("warmup_per_cohort", 10); put("measured_per_cohort", 50)
            put("api_requests", 0); put("tts_requests", 0); put("wire_journal_limit", 200)
            putJsonArray("excluded") {
                listOf("UI rendering/subtitle display", "RoomPresentation scheduling/playback",
                    "AI inference", "TTS generation/audio output", "internet/LAN latency", "human response time").forEach { add(it) }
            }
            put("spoken_metric", "first StateFlow snapshot containing new confirmed isSpoken journal entries; not on-screen subtitles")
            put("observation", "StateFlow collector scheduling is included; values are not raw packet timestamps")
            putJsonArray("summaries") { (2..4).forEach { count -> add(summary(samples.filter { it.clients == count })) } }
            putJsonArray("samples") { samples.forEach { add(it.json()) } }
        }
        val output = root.resolve("server/build/reports/tcp-latency-baseline.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println(buildJsonObject { put("baseline", "tcp-look"); put("samples", samples.size); put("cohorts", 3); put("api_requests", 0) })
    }
}
