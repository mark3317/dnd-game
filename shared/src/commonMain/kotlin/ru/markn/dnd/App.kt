package ru.markn.dnd

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.markn.dnd.adventure.AdventureViewModel
import ru.markn.dnd.adventure.ui.AdventureScreen
import ru.markn.dnd.adventure.ui.AdventureTheme
import ru.markn.dnd.network.*

@Composable
fun App() {
    val network = viewModel { NetworkViewModel() }
    val networkState by network.client.state.collectAsStateWithLifecycle()
    var local by rememberSaveable { mutableStateOf(false) }
    AdventureTheme {
        Surface(Modifier.fillMaxSize()) {
            if (local) Column(Modifier.fillMaxSize()) {
                val adventure = viewModel { AdventureViewModel() }
                TextButton(onClick = { local = false }) { Text("К сетевой игре") }
                Box(Modifier.weight(1f)) {
                    AdventureScreen(
                        state = adventure.state,
                        onSubmit = adventure::submit,
                        onRoll = adventure::roll,
                        onCancelCheck = adventure::cancelCheck,
                        onEndTurn = adventure::endTurn,
                        onRestart = adventure::restart,
                    )
                }
            } else NetworkScreen(network.client, networkState, onLocal = { local = true })
        }
    }
}
