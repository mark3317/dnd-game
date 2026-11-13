package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.markn.dnd.adventure.Scenarios

@Composable
fun ScenarioPicker(selectedId: String?, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Выберите приключение", style = MaterialTheme.typography.titleLarge)
        Scenarios.all.forEach { scenario ->
            OutlinedButton(onClick = { onSelect(scenario.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text((if (selectedId == scenario.id) "✓ " else "") + scenario.title)
            }
            Text(scenario.goal, style = MaterialTheme.typography.bodySmall)
        }
    }
}
