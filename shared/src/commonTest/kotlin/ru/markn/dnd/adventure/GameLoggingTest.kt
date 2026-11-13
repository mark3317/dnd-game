package ru.markn.dnd.adventure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.markn.dnd.adventure.diagnostics.GameLogEvent
import ru.markn.dnd.adventure.diagnostics.GameLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameLoggingTest {
    private class RecordingLogger : GameLogger {
        val events = mutableListOf<GameLogEvent>()
        override fun log(event: GameLogEvent) { events += event }
    }

    @Test
    fun sessionAndTurnEventsContainCorrelatedState() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(logger = logger)
        val initial = engine.newGame(sessionId = 42)
        assertEquals("session_started", logger.events.single().name)
        assertEquals("42", logger.events.single().fields["session"])
        val next = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.LOOK.text)
        val changed = logger.events.last { it.name == "turn_changed" }
        assertEquals(initial.activePlayer.id, changed.fields["previous_player"])
        assertEquals(next.activePlayer.id, changed.fields["next_player"])
        assertEquals("2", changed.fields["turn"])
        assertEquals("1", changed.fields["round"])
    }

    @Test
    fun checkEventsRecordOwnersNumbersAndConsequences() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(DiceRoller { 7 }, logger)
        val initial = engine.newGame()
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.CLIMB.text)
        val requested = logger.events.last { it.name == "check_requested" }
        assertEquals(initial.activePlayer.id, requested.fields["player"])
        assertEquals("10", requested.fields["difficulty"])
        engine.roll(pending, pending.activePlayer.id, pending.pendingCheck!!.id)
        val result = logger.events.last { it.name == "check_resolved" }
        assertEquals(requested.fields["check_id"], result.fields["check_id"])
        assertEquals("7", result.fields["die"])
        assertEquals("3", result.fields["modifier"])
        assertEquals("10", result.fields["total"])
        assertEquals("true", result.fields["succeeded"])
        val hero = logger.events.last { it.name == "hero_changed" }
        assertEquals(initial.activePlayer.id, hero.fields["player"])
        assertEquals("SHORE", hero.fields["location_before"])
        assertEquals("LANTERN_ROOM", hero.fields["location_after"])
    }

    @Test
    fun wrongActorAndStaleTurnHaveDistinctRejectionReasons() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(logger = logger)
        val initial = engine.newGame()
        assertSame(initial, engine.endTurn(initial, initial.players[1].id, initial.turnId))
        assertEquals("wrong_player", logger.events.last().fields["reason"])
        var next = initial
        repeat(initial.players.size) { next = engine.endTurn(next, next.activePlayer.id, next.turnId) }
        assertSame(next, engine.submit(next, initial.activePlayer.id, initial.turnId, DemoAction.LOOK.text))
        assertEquals("command_rejected", logger.events.last().name)
        assertEquals("stale_turn", logger.events.last().fields["reason"])
        assertEquals("1", logger.events.last().fields["requested_turn"])
    }

    @Test
    fun playerTextIsNotCopiedIntoDiagnosticEvents() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(logger = logger)
        val initial = engine.newGame()
        val privateText = "личная реплика с секретом\nи продолжением"
        engine.submit(initial, initial.activePlayer.id, initial.turnId, privateText)
        val values = logger.events.flatMap { event ->
            val json = Json.parseToJsonElement(event.toJsonLine()).jsonObject
            listOf(json.getValue("event"), json.getValue("timestamp")) + json.getValue("fields").jsonObject.values
        }.map { it.jsonPrimitive.content }
        assertFalse(values.any { it.contains("секретом") })
        assertFalse(values.any { it.contains("личная реплика") })
        assertFalse(values.any { it.contains(initial.activePlayer.hero.name) })
        val command = logger.events.last { it.name == "command_completed" }
        assertEquals("unsupported", command.fields["outcome"])
        assertEquals(privateText.length.toString(), command.fields["input_length"])
    }

    @Test
    fun cancellationDoesNotProduceFakeRollOrTurnEvents() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(logger = logger)
        val initial = engine.newGame()
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.CLIMB.text)
        val count = logger.events.size
        val cancelled = engine.cancelCheck(pending, pending.activePlayer.id, pending.pendingCheck!!.id)
        val added = logger.events.drop(count)
        assertEquals(listOf("command_completed"), added.map { it.name })
        assertEquals("cancel_check", added.single().fields["command"])
        assertEquals(initial.turnId, cancelled.turnId)
    }

    @Test
    fun loggingFailureCannotBreakValidGameActions() {
        val engine = AdventureEngine(
            dice = DiceRoller { 7 },
            logger = GameLogger { error("Log output unavailable") },
        )
        val initial = engine.newGame()
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.CLIMB.text)
        val next = engine.roll(pending, pending.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(Location.LANTERN_ROOM, next.players.first().location)
        assertEquals(initial.players[1].id, next.activePlayer.id)
    }

    @Test
    fun diceFailureIsRecordedWithoutAFalseResult() {
        val logger = RecordingLogger()
        val engine = AdventureEngine(DiceRoller { 21 }, logger)
        val initial = engine.newGame()
        val pending = engine.submit(initial, initial.activePlayer.id, initial.turnId, DemoAction.CLIMB.text)
        assertFailsWith<IllegalArgumentException> { engine.roll(pending, pending.activePlayer.id, pending.pendingCheck!!.id) }
        assertEquals("dice_failed", logger.events.last().name)
        assertEquals("invalid_result", logger.events.last().fields["reason"])
        assertFalse(logger.events.any { it.name == "check_resolved" })
    }

    @Test
    fun jsonLinesRoundTripSpecialCharactersWithoutBreakingLineFraming() {
        val name = "событие \"кубик\"\nновая строка"
        val key = "ключ\"\\\t"
        val value = "Кавычки: \"; слеш: \\; Unicode: маяк 🔦; " +
            (0..31).map { it.toChar() }.joinToString("")
        val timestamp = "2026-11-13T00:00:00Z"
        val line = GameLogEvent(name, mapOf(key to value), timestamp).toJsonLine()
        val event = Json.parseToJsonElement(line).jsonObject

        assertEquals(setOf("timestamp", "event", "fields"), event.keys)
        assertEquals(timestamp, event.getValue("timestamp").jsonPrimitive.content)
        assertEquals(name, event.getValue("event").jsonPrimitive.content)
        assertEquals(value, event.getValue("fields").jsonObject.getValue(key).jsonPrimitive.content)
        assertFalse(line.any { it.code < 0x20 }, "JSONL must not contain literal control characters")
    }

    @Test
    fun emptyFieldsRemainAnObjectInTheLogSchema() {
        val event = Json.parseToJsonElement(
            GameLogEvent(name = "session_started", timestamp = "fixed-time").toJsonLine(),
        ).jsonObject
        assertEquals(setOf("timestamp", "event", "fields"), event.keys)
        assertEquals("session_started", event.getValue("event").jsonPrimitive.content)
        assertEquals("fixed-time", event.getValue("timestamp").jsonPrimitive.content)
        assertTrue(event.getValue("fields").jsonObject.isEmpty())
    }

    @Test
    fun numericBooleanAndNullLookingFieldsRemainJsonStrings() {
        val original = mapOf("die" to "7", "leading_zero" to "001", "succeeded" to "true", "empty" to "", "null_text" to "null")
        val fields = Json.parseToJsonElement(GameLogEvent("check", original).toJsonLine())
            .jsonObject.getValue("fields").jsonObject
        assertEquals(original.keys, fields.keys)
        original.forEach { (key, value) ->
            val field = fields.getValue(key).jsonPrimitive
            assertTrue(field.isString, "The existing schema stores $key as a string")
            assertEquals(value, field.content)
        }
    }
}
