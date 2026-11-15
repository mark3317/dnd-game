package ru.markn.dnd.adventure.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Explicit fade: built-in transitions cannot inherit a clock from a CompositionLocal. */
@Composable
internal fun <T> PausableCrossfade(target: T, modifier: Modifier = Modifier, millis: Int = 250,
    contentKey: (T) -> Any? = { it }, content: @Composable (T) -> Unit) {
    val targetKey = contentKey(target)
    val values = remember { mutableStateMapOf(targetKey to target) }
    values[targetKey] = target
    Box(modifier) {
        values.toMap().forEach { (id, value) -> key(id) {
            val alpha = remember { Animatable(if (id == targetKey && values.size == 1) 1f else 0f) }
            val clock = LocalPresentationPauseClock.current
            val current = id == targetKey
            LaunchedEffect(current, millis) {
                clock.presentationAnimation { alpha.animateTo(if (current) 1f else 0f, tween(millis)) }
                if (!current) values.remove(id)
            }
            Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content(value) }
        } }
    }
}

@Composable
internal fun PausableVisibility(visible: Boolean, modifier: Modifier = Modifier,
    enterMillis: Int = 240, exitMillis: Int = 220, onHidden: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(0f) }
    val clock = LocalPresentationPauseClock.current
    val hiddenCallback by rememberUpdatedState(onHidden)
    LaunchedEffect(visible) {
        if (visible) mounted = true
        clock.presentationAnimation { alpha.animateTo(if (visible) 1f else 0f, tween(if (visible) enterMillis else exitMillis)) }
        if (!visible) { mounted = false; hiddenCallback?.invoke() }
    }
    if (mounted) Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}
