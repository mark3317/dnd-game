package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

private val presentationTimeOrigin = TimeSource.Monotonic.markNow()
private fun presentationMonotonicMillis() = presentationTimeOrigin.elapsedNow().inWholeMilliseconds

/** Local presentation time. Session state and server epoch timestamps never use this clock. */
internal class PresentationPauseClock(private val time: () -> Long = ::presentationMonotonicMillis) {
    private var accumulatedPause = 0L
    private var pauseStarted: Long? = null
    private val running = MutableStateFlow(true)
    val paused: Boolean get() = !running.value
    fun nowMillis(): Long = (pauseStarted ?: time()) - accumulatedPause
    fun setPaused(value: Boolean) {
        if (value == paused) return
        if (value) pauseStarted = time() else {
            accumulatedPause += (time() - checkNotNull(pauseStarted)).coerceAtLeast(0)
            pauseStarted = null
        }
        running.value = !value
    }
    suspend fun awaitRunning() { running.first { it } }
    suspend fun delayMillis(duration: Long) {
        val until = nowMillis() + duration.coerceAtLeast(0)
        while (true) {
            awaitRunning()
            val remaining = until - nowMillis()
            if (remaining <= 0) return
            delay(minOf(remaining, 16L))
        }
    }
    /** Explicit animations retain their frame position across transport pause. */
    suspend fun <T> animate(block: suspend () -> T): T {
        val parent = checkNotNull(currentCoroutineContext()[MonotonicFrameClock])
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                awaitRunning()
                parent.withFrameNanos { it }
                awaitRunning()
                return onFrame(nowMillis() * 1_000_000L)
            }
        }
        return withContext(frameClock) { block() }
    }
}

internal val LocalPresentationPauseClock = staticCompositionLocalOf<PresentationPauseClock?> { null }
internal suspend fun PresentationPauseClock?.presentationDelay(millis: Long) {
    if (this == null) delay(millis) else delayMillis(millis)
}
internal suspend fun <T> PresentationPauseClock?.presentationAnimation(block: suspend () -> T): T =
    if (this == null) block() else animate(block)

/** Decorative loop driven by the same explicit pause clock as confirmed events. */
@Composable
internal fun pausedLoop(from: Float, to: Float, millis: Int, enabled: Boolean = true): Float {
    val value = remember { androidx.compose.animation.core.Animatable(from) }
    val clock = LocalPresentationPauseClock.current
    LaunchedEffect(enabled, millis) {
        if (!enabled) value.snapTo(from) else {
            while (true) {
                clock.presentationAnimation { value.animateTo(to, androidx.compose.animation.core.tween(millis)) }
                clock.presentationAnimation { value.animateTo(from, androidx.compose.animation.core.tween(millis)) }
            }
        }
    }
    return if (enabled) value.value else from
}
