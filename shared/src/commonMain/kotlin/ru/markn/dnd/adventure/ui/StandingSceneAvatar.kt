package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun StandingSceneAvatar(
    id: String,
    name: String,
    portrait: DrawableResource?,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
) {
    key(id) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        var tapped by remember { mutableStateOf(false) }
        val pauseClock = LocalPresentationPauseClock.current
        LaunchedEffect(tapped) {
            if (tapped) {
                pauseClock.presentationDelay(2500)
                tapped = false
            }
        }
        BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
            // Stage masters are 640x1024 with the feet at y=1008. Move that
            // transparent bottom margin below the stage, keeping feet on its edge.
            val interactionModifier = Modifier.hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null,
                    onClickLabel = "Показать имя") { tapped = !tapped }
            if (portrait != null) {
                Image(painterResource(portrait), name,
                    Modifier.requiredSize(maxHeight * .625f, maxHeight)
                        .then(interactionModifier).offset(y = maxHeight / 64),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter)
            } else {
                Surface(Modifier.size(66.dp).then(interactionModifier), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(name.take(1), style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
            if (hovered || tapped) {
                Surface(Modifier.align(if (portrait != null) Alignment.TopCenter else Alignment.BottomCenter)
                    .graphicsLayer {
                        // Draw above the figure without reserving space or moving its feet.
                        translationY = -size.height - (if (portrait != null) 8.dp else 74.dp).toPx()
                    }, shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = .68f)) {
                    Text(name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (speaking) AdventureGold else Color.White,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
