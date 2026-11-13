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
import ru.markn.dnd.adventure.ui.ScenarioPicker
import androidx.compose.ui.unit.dp
import ru.markn.dnd.network.*

@Composable
fun App() {
    val network = viewModel { NetworkViewModel() }
    val networkState by network.client.state.collectAsStateWithLifecycle()
    var local by rememberSaveable { mutableStateOf(false) }
    var localScenarioChosen by rememberSaveable { mutableStateOf(false) }
    var confirmScenarioChange by remember { mutableStateOf(false) }
    AdventureTheme {
        Surface(Modifier.fillMaxSize()) {
            if (local) Column(Modifier.fillMaxSize()) {
                val adventure = viewModel { AdventureViewModel() }
                TextButton(onClick = { local = false }) { Text("К сетевой игре") }
                if (localScenarioChosen) TextButton(onClick = { confirmScenarioChange = true }) { Text("Другой сюжет") }
                if (!localScenarioChosen) Box(Modifier.padding(24.dp)) {
                    ScenarioPicker(null) { id -> adventure.selectScenario(id); localScenarioChosen = true }
                } else Box(Modifier.weight(1f)) {
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
            if (confirmScenarioChange) AlertDialog(
                onDismissRequest = { confirmScenarioChange = false },
                title = { Text("Выбрать другой сюжет?") },
                text = { Text("Текущая локальная партия будет заменена новой после выбора сюжета.") },
                confirmButton = { TextButton(onClick = { confirmScenarioChange = false; localScenarioChosen = false }) { Text("Выбрать сюжет") } },
                dismissButton = { TextButton(onClick = { confirmScenarioChange = false }) { Text("Остаться") } },
            )
        }
    }
}
