package ru.markn.dnd

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Истории у костра",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        App(onExit = ::exitApplication)
    }
}
