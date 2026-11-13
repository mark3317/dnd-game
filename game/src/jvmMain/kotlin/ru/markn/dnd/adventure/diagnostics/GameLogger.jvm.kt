package ru.markn.dnd.adventure.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.SimpleFormatter
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import co.touchlab.kermit.platformLogWriter
import java.nio.file.Files
import java.nio.file.Path

internal actual fun gameLogWriters(): List<LogWriter> = buildList {
    add(platformLogWriter())
    try {
        val configured = System.getProperty("dnd.game.logDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("DND_GAME_LOG_DIR")?.takeIf { it.isNotBlank() }
        val directory = if (configured != null) Path.of(configured) else {
            val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            Path.of(base, "dnd-game", "logs")
        }
        add(createGameFileWriter(directory))
        println("[dnd-game] log_file=${directory.toAbsolutePath().resolve("game.log")}")
    } catch (failure: Exception) {
        System.err.println("[dnd-game] log_file_unavailable type=${failure.javaClass.simpleName}")
    }
}

/** Kermit owns asynchronous writing, bounded buffering and file rotation. */
internal fun createGameFileWriter(directory: Path): LogWriter {
    Files.createDirectories(directory)
    return RollingFileLogWriter(
        config = RollingFileLogWriterConfig(
            logFileName = "game",
            logFilePath = kotlinx.io.files.Path(directory.toAbsolutePath().toString()),
            rollOnSize = 5L * 1024 * 1024,
            maxLogFiles = 2,
            logTag = false,
            prependTimestamp = false,
        ),
        messageStringFormatter = SimpleFormatter,
    )
}