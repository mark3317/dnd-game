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
import org.jetbrains.compose.resources.DrawableResource
import ru.markn.dnd.adventure.*
import ru.markn.dnd.generated.resources.*

private data class StageAvatar(val id: String, val name: String, val portrait: DrawableResource?, val height: Float = 1f)

@Composable
fun ArchiveScene(state: AdventureState, modifier: Modifier = Modifier, spokenEntry: JournalEntry? = state.lastSpokenEntry) {
    val image = when {
        state.ending == FloodedArchive.FLOODED_ENDING -> Res.drawable.archive_cellar_flooded_ending
        state.ending == FloodedArchive.ORIGINALS_ENDING -> Res.drawable.archive_cellar_documents_saved
        state.location == FloodedArchive.YARD -> Res.drawable.archive_courier_yard
        state.location == FloodedArchive.CELLAR && FloodedArchive.CHEST_OPEN in state.scene.facts -> Res.drawable.archive_cellar_chest_open
        state.location == FloodedArchive.CELLAR -> Res.drawable.archive_cellar_chest_closed
        FloodedArchive.DOOR_OPEN in state.scene.facts -> Res.drawable.archive_hall_open
        else -> Res.drawable.archive_hall_locked
    }
    val avatars = state.players.filter { it.id == state.activePlayer.id && it.location == state.location }.map { player ->
        StageAvatar(player.id, player.hero.name, player.hero.profile?.let(::standingResource),
            player.hero.profile?.let(::standingHeight) ?: 1f)
    }.toMutableList()
    val site = state.scenario.sites.getValue(state.location)
    if (state.location != FloodedArchive.CELLAR && state.ending != ArchiveDemo.DEFEAT && site.npcId != null) {
        val courier = state.location == FloodedArchive.YARD
        avatars += StageAvatar(requireNotNull(site.npcId), site.npcName ?: if (courier) "Егор" else "Агата",
            if (courier) Res.drawable.stage_archive_courier else Res.drawable.stage_archive_archivist)
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        MovingStoryImage(image, site.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .60f)))))
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp)
            .fillMaxWidth().height((maxHeight - 155.dp).coerceAtLeast(1.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            avatars.forEach { avatar ->
                val speaking = spokenEntry?.speakerNpcId == avatar.id
                StandingSceneAvatar(avatar.id, avatar.name, avatar.portrait,
                    Modifier.weight(1f).fillMaxHeight(avatar.height), speaking)
            }
        }
    }
}
