package ru.markn.dnd

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.markn.dnd.adventure.AdventureViewModel
import ru.markn.dnd.adventure.ui.AdventureScreen
import ru.markn.dnd.adventure.ui.AdventureTheme

@Composable
fun App() {
    val adventure = viewModel { AdventureViewModel() }
    AdventureTheme {
        AdventureScreen(
            state = adventure.state,
            onSubmit = adventure::submit,
            onRoll = adventure::roll,
            onCancelCheck = adventure::cancelCheck,
            onEndTurn = adventure::endTurn,
            onRestart = adventure::restart,
        )
    }
}