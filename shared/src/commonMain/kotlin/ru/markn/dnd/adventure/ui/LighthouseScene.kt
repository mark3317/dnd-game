package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.markn.dnd.adventure.AdventureState
import ru.markn.dnd.generated.resources.*

@Composable
internal fun LighthouseScene(state: AdventureState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val figureHeight = (maxHeight - 174.dp).coerceAtLeast(1.dp)
        MovingStoryImage(Res.drawable.lighthouse_establishing, "Маяк над туманной рекой", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC008121A)))))
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
            state.players.filter { it.id == state.activePlayer.id && it.location == state.location }.forEach { player ->
                val profile = player.hero.profile
                if (profile != null) StandingSceneAvatar(player.id, player.hero.name, standingResource(profile),
                    Modifier.weight(1f).height(figureHeight * standingHeight(profile)))
            }
        }
    }
}
