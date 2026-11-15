package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.*

@Composable
fun LocalAdventureSetup(library: CharacterLibrary, initialParty: List<CharacterProfile>,
                        onStart: (String, List<CharacterProfile>) -> Unit) {
    var scenarioId by rememberSaveable { mutableStateOf(ArchiveDemo.ID) }
    var serialized by rememberSaveable { mutableStateOf(Json.encodeToString<List<CharacterProfile?>>(initialParty)) }
    var editing by rememberSaveable { mutableStateOf(0) }
    val party = Json.decodeFromString<List<CharacterProfile?>>(serialized)
    fun update(next: List<CharacterProfile?>) { serialized = Json.encodeToString(next) }
    Column(Modifier.fillMaxHeight().widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScenarioPicker(scenarioId) { scenarioId = it }
        Text("Группа: ${party.size} игрока", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(party + listOf(null)); editing = party.size }, enabled = party.size < 4) { Text("Добавить игрока") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(party.dropLast(1)); editing = editing.coerceAtMost(party.size - 2) }, enabled = party.size > 2) { Text("Убрать последнего") }
        }
        party.forEachIndexed { index, profile ->
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { editing = index }) { Text("Игрок ${index + 1}: ${profile?.name ?: "выберите героя"}") }
        }
        Text("Герой игрока ${editing + 1}", style = MaterialTheme.typography.titleMedium)
        CharacterPicker(library, party[editing], unavailableNames = party.filterIndexed { index, _ -> index != editing }.mapNotNull { it?.name }) { profile ->
            update(party.mapIndexed { index, previous -> if (index == editing) profile else previous })
        }
        Button(shape = MaterialTheme.shapes.small, onClick = { onStart(scenarioId, party.filterNotNull()) }, enabled = party.all { it != null } &&
            party.mapNotNull { it?.name?.lowercase() }.distinct().size == party.size,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Начать историю") }
        if (party.any { it == null }) Text("Выберите героя для каждого игрока.", color = AdventureMuted)
    }
}
