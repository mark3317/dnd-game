package ru.markn.dnd.adventure.diagnostics

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

data class GameLogEvent(
    val name: String,
    val fields: Map<String, String> = emptyMap(),
    val timestamp: String = Clock.System.now().toString(),
) {
    /** One JSON object per line, including when an identifier contains control characters. */
    fun toJsonLine(): String = buildJsonObject {
        put("timestamp", timestamp)
        put("event", name)
        putJsonObject("fields") {
            fields.forEach { (key, value) -> put(key, value) }
        }
    }.toString()
}

fun interface GameLogger {
    fun log(event: GameLogEvent)

    companion object {
        val None = GameLogger { }
        val Platform = GameLogger { event ->
            val logger = PlatformGameLog.logger
            when {
                event.name.endsWith("_failed") -> logger.e { event.toJsonLine() }
                event.name == "command_rejected" -> logger.w { event.toJsonLine() }
                else -> logger.i { event.toJsonLine() }
            }
        }
    }
}

internal expect fun gameLogWriters(): List<LogWriter>

private object PlatformGameLog {
    val logger: Logger by lazy {
        Logger(StaticConfig(minSeverity = Severity.Info, logWriterList = gameLogWriters()), "DndGame")
    }
}
