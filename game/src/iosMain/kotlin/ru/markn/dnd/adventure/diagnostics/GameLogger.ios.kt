package ru.markn.dnd.adventure.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.platformLogWriter

internal actual fun gameLogWriters(): List<LogWriter> = listOf(platformLogWriter())