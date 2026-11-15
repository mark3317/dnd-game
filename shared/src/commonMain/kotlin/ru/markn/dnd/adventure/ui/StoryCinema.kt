package ru.markn.dnd.adventure.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.adventure.*
import ru.markn.dnd.generated.resources.*

internal fun storyImage(art: StoryArt): DrawableResource? = when (art) {
    StoryArt.EXTERIOR -> Res.drawable.archive_exterior_night
    StoryArt.HALL -> Res.drawable.archive_hall_locked
    StoryArt.YARD -> Res.drawable.archive_courier_yard
    StoryArt.DOCUMENTS -> Res.drawable.archive_cellar_documents_saved
    StoryArt.FLOOD -> Res.drawable.archive_cellar_flooded_ending
    StoryArt.DAWN -> Res.drawable.archive_dawn
    StoryArt.LIGHTHOUSE -> Res.drawable.lighthouse_establishing
    StoryArt.LOCATION -> Res.drawable.river_epilogue
}

@Composable
internal fun MovingStoryImage(image: DrawableResource, description: String, modifier: Modifier = Modifier, motion: Boolean = LocalGameMotion.current) {
    val movement = rememberInfiniteTransition(label = "slow camera")
    val progress by movement.animateFloat(0f, 1f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse), label = "camera")
    Box(modifier.clipToBounds()) {
        Crossfade(image, animationSpec = tween(1200), label = "location dissolve") { frame ->
            Image(painterResource(frame), description, Modifier.fillMaxSize().graphicsLayer {
                scaleX = if (motion) 1.06f + progress * .045f else 1f
                scaleY = scaleX
                translationX = if (motion) size.width * .012f * (progress - .5f) else 0f
            }, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun StoryCinema(state: AdventureState, ending: Boolean, now: Long, speech: TimedSpeech? = null) {
    val shots = remember(state.sessionId, state.scenarioId, state.ending) { if (ending) state.endingShots() else state.openingShots() }
    val start = if (ending) state.playback.endingAt else state.playback.openingAt
    var cursor = start
    var selected = shots.lastOrNull()
    var elapsed = 0L
    for (shot in shots) {
        val end = cursor + subtitleDuration(shot.text)
        selected = shot
        elapsed = now - cursor
        if (now < end) break
        cursor = end
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() } }
    }) {
        val image = storyImage(selected?.art ?: StoryArt.LOCATION)
        if (image != null) MovingStoryImage(image, state.scenario.title, Modifier.fillMaxSize())
        selected?.let { StorySubtitle(speech?.entry?.text ?: it.text, null,
            if (speech != null) now - speech.startsAt else elapsed,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) }
    }
}
