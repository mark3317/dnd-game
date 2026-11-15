package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.generated.resources.*
import ru.markn.dnd.adventure.*

@Composable
fun ScenarioPicker(selectedId: String?, enabled: Boolean = true, onlySelected: Boolean = false,
    actionLabel: String = "Выбрать историю", unavailableHint: String = "Историю выбирает создатель комнаты", onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AdventureSectionTitle(if (onlySelected) "Ваша история" else "Выберите приключение")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Scenarios.available.filter { !onlySelected || it.id == selectedId }.forEach { scenario ->
            OutlinedCard(onClick = { onSelect(scenario.id) }, enabled = enabled && !onlySelected,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = AdventurePanelColor.copy(alpha = .96f),
                    disabledContainerColor = AdventurePanelColor.copy(alpha = .96f),
                    disabledContentColor = AdventureParchment,
                ),
                border = BorderStroke(if (selectedId == scenario.id) 2.dp else 1.dp,
                    if (selectedId == scenario.id) AdventureGold else MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth().height(180.dp)) {
                    Image(painterResource(if (scenario.id == Lighthouse.ID) Res.drawable.lighthouse_establishing else Res.drawable.archive_exterior_night),
                        scenario.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD10191F)))))
                    Text(if (selectedId == scenario.id) "ВЫБРАНО  ◆" else if (scenario.id == ArchiveDemo.ID) "ПОЛНОЕ ПРИКЛЮЧЕНИЕ" else "КОРОТКАЯ ИСТОРИЯ",
                        Modifier.align(Alignment.BottomStart).padding(16.dp), color = AdventureGold, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(scenario.title, style = MaterialTheme.typography.titleLarge)
                    Text(scenario.goal, style = MaterialTheme.typography.bodyMedium, minLines = 2)
                    Text("2–4 игрока · ${if (scenario.id == ArchiveDemo.ID) "40–60 минут · исследование и бой" else "загадки и решения"}", color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
                    if (!onlySelected) Text(if (enabled) "$actionLabel  →" else unavailableHint, color = AdventureGold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        }
    }
}
