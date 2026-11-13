package ru.markn.dnd.adventure.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val AdventureGold = Color(0xFFE8B975)
internal val AdventureMuted = Color(0xFF9BACB4)
internal val AdventureGreen = Color(0xFF9CC8AB)

@Composable
fun AdventureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AdventureGold,
            onPrimary = Color(0xFF302312),
            secondary = AdventureGreen,
            background = Color(0xFF10191F),
            onBackground = Color(0xFFF0E9DD),
            surface = Color(0xFF19252D),
            onSurface = Color(0xFFF0E9DD),
            surfaceVariant = Color(0xFF24343E),
            onSurfaceVariant = Color(0xFFB7C3C8),
            outline = Color(0xFF40505A),
            error = Color(0xFFF2A79C),
        ),
        content = content,
    )
}