package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalReducedMotion = staticCompositionLocalOf { false }

/** Presentation-only profile. Reading/voice clocks never use transitionMillis(). */
internal data class GameMotionProfile(val reduced: Boolean) {
    fun transitionMillis(normal: Int): Int = if (reduced) 0 else normal
    fun cameraActive(cameraEnabled: Boolean): Boolean = cameraEnabled && !reduced
    val diceWindupMillis: Int get() = if (reduced) 0 else 1450
    val diceResultMillis: Long get() = 1800L
    val interactionWindupMillis: Int get() = if (reduced) 0 else 2200
    val interactionResultMillis: Long get() = 2400L
}
