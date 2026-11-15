package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MediaGenerationSetting() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Потоковая генерация медиа", style = MaterialTheme.typography.titleMedium)
                Text("Генерация картинок ярких событий и дополнительные облачные медиа.", style = MaterialTheme.typography.bodySmall)
                Text("Пока недоступно. Бесплатная озвучка Qwen включается отдельно кнопкой «Озвучка» в истории.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = false, onCheckedChange = null, enabled = false)
        }
    }
}
