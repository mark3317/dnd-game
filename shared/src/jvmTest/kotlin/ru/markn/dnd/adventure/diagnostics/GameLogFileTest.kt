package ru.markn.dnd.adventure.diagnostics

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.markn.dnd.adventure.AdventureEngine
import ru.markn.dnd.adventure.DemoAction
import ru.markn.dnd.adventure.DiceRoller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameLogFileTest {
    @Test
    fun configuredKermitWriterProducesReadableGameDiagnosticsWithoutUi() {
        val directory = Path.of("build", "test-logs", "run-${System.nanoTime()}").toAbsolutePath()
        val output = directory.resolve("game.log")
        val kermit = Logger(
            StaticConfig(minSeverity = Severity.Info, logWriterList = listOf(createGameFileWriter(directory))),
            "DndGame",
        )
        val engine = AdventureEngine(DiceRoller { 7 }, GameLogger { event -> kermit.i { event.toJsonLine() } })
        val initial = engine.newGame()
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.CLIMB.text)
        val checkId = requireNotNull(pending.pendingCheck).id
        val next = engine.roll(pending, pending.activePlayer.id, checkId)
        engine.roll(next, initial.activePlayer.id, checkId)

        fun readEvents(): List<JsonObject> = if (Files.exists(output)) {
            Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject }
        } else {
            emptyList()
        }

        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            // The asynchronous writer may be in the middle of writing its last line.
            val snapshot = runCatching { readEvents() }.getOrDefault(emptyList())
            if (snapshot.any { it["event"]?.jsonPrimitive?.content == "command_rejected" }) break
            Thread.sleep(10)
        }
        assertTrue(Files.exists(output), "Kermit must create the configured file")
        val events = readEvents()
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            assertEquals(setOf("timestamp", "event", "fields"), event.keys)
            assertTrue(event.getValue("timestamp").jsonPrimitive.isString)
            assertTrue(event.getValue("event").jsonPrimitive.isString)
            assertTrue(event.getValue("fields").jsonObject.values.all { it.jsonPrimitive.isString })
        }

        val roll = events.single { it.getValue("event").jsonPrimitive.content == "check_resolved" }
            .getValue("fields").jsonObject
        assertEquals(initial.activePlayer.id, roll.getValue("player").jsonPrimitive.content)
        assertEquals(checkId.actionId.toString(), roll.getValue("check_id").jsonPrimitive.content)
        assertEquals("7", roll.getValue("die").jsonPrimitive.content)
        assertEquals("3", roll.getValue("modifier").jsonPrimitive.content)
        assertEquals("10", roll.getValue("total").jsonPrimitive.content)
        assertEquals("10", roll.getValue("difficulty").jsonPrimitive.content)
        assertEquals("true", roll.getValue("succeeded").jsonPrimitive.content)

        val changed = events.single { it.getValue("event").jsonPrimitive.content == "turn_changed" }
            .getValue("fields").jsonObject
        assertEquals(initial.activePlayer.id, changed.getValue("previous_player").jsonPrimitive.content)
        assertEquals(next.activePlayer.id, changed.getValue("next_player").jsonPrimitive.content)

        val rejected = events.single { it.getValue("event").jsonPrimitive.content == "command_rejected" }
            .getValue("fields").jsonObject
        assertEquals("roll", rejected.getValue("command").jsonPrimitive.content)
        assertEquals("no_pending_check", rejected.getValue("reason").jsonPrimitive.content)
        println("GAME_DIAGNOSTICS_SAMPLE=$output")
    }
}