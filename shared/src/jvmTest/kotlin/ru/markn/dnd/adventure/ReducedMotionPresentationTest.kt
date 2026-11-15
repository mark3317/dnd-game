package ru.markn.dnd.adventure

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.*
import ru.markn.dnd.adventure.ui.*

@OptIn(ExperimentalComposeUiApi::class)
class ReducedMotionPresentationTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun texts(scene: ImageComposeScene): List<String> = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    @Test fun togglingDuringRollKeepsAuthorityResultReadingWindowAndOneSoundPerPhase() {
        var reduced by mutableStateOf(false)
        var done = 0
        val sounds = mutableListOf<String>()
        val event = DiceEvent("Проверка", "Рин", listOf(4, 17), 20, bonus = 3,
            difficulty = 20, success = true, outcome = "Успех", best = true)
        val scene = ImageComposeScene(620, 700) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                DiceRollOverlay(event, 1, soundOutput = { phase, _ -> sounds += phase }, onDone = { done++ })
            }
        }
        try {
            val start = System.nanoTime()
            var sawConfirmedResult = false
            while ((System.nanoTime() - start) < 2_700_000_000L) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                reduced = elapsed in 150L..699L || elapsed >= 1100
                scene.render(System.nanoTime()).close()
                if (elapsed in 300L..1000L) {
                    val content = texts(scene)
                    assertTrue(content.contains("17"))
                    assertTrue(content.any { it.contains("max(4, 17)") && it.contains("= 20") })
                    sawConfirmedResult = true
                }
                if (done > 0) {
                    assertTrue(elapsed >= 1800, "Reduced motion must retain time to read the result")
                    break
                }
                Thread.sleep(12)
            }
            assertTrue(sawConfirmedResult)
            assertEquals(1, done, "Mode toggles must not restart the reading window or lose completion")
            assertEquals(listOf("dice:cast", "dice:land"), sounds)
        } finally { scene.close() }
    }

    @Test fun pausedResultRetainsRemainingReadingTimeAndCompletesOnce() {
        val clock = PresentationPauseClock()
        var done = 0
        val sounds = mutableListOf<String>()
        val event = DiceEvent("Проверка", "Рин", listOf(17), 20, outcome = "Подтверждённый итог")
        val scene = ImageComposeScene(620, 700) {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalPresentationPauseClock provides clock) {
                DiceRollOverlay(event, 1, soundOutput = { phase, _ -> sounds += phase }, onDone = { done++ })
            }
        }
        try {
            val start = System.nanoTime()
            var frozenAt: Long? = null
            while (System.nanoTime() - start < 4_000_000_000L) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                clock.setPaused(elapsed in 250L..1149L)
                if (clock.paused && frozenAt == null) frozenAt = clock.nowMillis()
                scene.render(System.nanoTime()).close()
                if (elapsed in 400L..1000L) {
                    assertEquals(frozenAt, clock.nowMillis())
                    assertEquals(0, done)
                    assertTrue(texts(scene).contains("Подтверждённый итог"))
                }
                if (done > 0) {
                    assertTrue(elapsed >= 2600, "900 ms pause must not consume the 1800 ms reading window")
                    break
                }
                Thread.sleep(12)
            }
            assertEquals(1, done)
            assertEquals(listOf("dice:land"), sounds)
        } finally { scene.close() }
    }

    @Test fun animationFrameClockResumesFromFrozenProgressWithoutJumpingToEnd() {
        val clock = PresentationPauseClock()
        val progress = androidx.compose.animation.core.Animatable(0f)
        var done = 0
        val scene = ImageComposeScene(200, 100) {
            LaunchedEffect(Unit) {
                clock.presentationAnimation { progress.animateTo(1f, androidx.compose.animation.core.tween(700)) }
                done++
            }
            androidx.compose.material3.Text(progress.value.toString())
        }
        try {
            val start = System.nanoTime()
            var frozen: Float? = null
            while (System.nanoTime() - start < 2_500_000_000L) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                clock.setPaused(elapsed in 200L..799L)
                scene.render(System.nanoTime()).close()
                if (elapsed in 300L..700L) {
                    if (frozen == null) frozen = progress.value
                    assertEquals(frozen, progress.value)
                    assertEquals(0, done)
                }
                if (elapsed in 820L..900L) assertTrue(progress.value < .8f)
                if (done > 0) { assertTrue(elapsed >= 1200); break }
                Thread.sleep(12)
            }
            assertNotNull(frozen)
            assertEquals(1, done)
        } finally { scene.close() }
    }

    @Test fun pauseDuringExitCannotAdvanceQueueBehindOverlay() {
        val clock = PresentationPauseClock()
        var visible by mutableStateOf(true)
        var hidden = 0
        val scene = ImageComposeScene(320, 200) {
            CompositionLocalProvider(LocalPresentationPauseClock provides clock) {
                PausableVisibility(visible, enterMillis = 0, exitMillis = 200, onHidden = { hidden++ }) {
                    androidx.compose.material3.Text("Результат")
                }
            }
        }
        try {
            val start = System.nanoTime()
            while (System.nanoTime() - start < 1_200_000_000L) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if (elapsed >= 100) visible = false
                clock.setPaused(elapsed in 150L..649L)
                scene.render(System.nanoTime()).close()
                if (elapsed in 300L..600L) {
                    assertEquals(0, hidden)
                    assertTrue(texts(scene).contains("Результат"))
                }
                if (hidden > 0) { assertTrue(elapsed >= 750); break }
                Thread.sleep(12)
            }
            assertEquals(1, hidden)
        } finally { scene.close() }
    }

    @Test fun zeroDurationEntryAndExitStillShowEveryQueuedDieBeforeAdvancing() {
        val events = listOf(8, 13).mapIndexed { i, die -> PresentedRoll("result:$i",
            DiceEvent("Проверка ${i + 1}", "Мира", listOf(die), 20, outcome = "Итог $die")) }
        val queue = RollPresentationQueue(emptyList()).apply { receive(events) }
        val scene = ImageComposeScene(620, 700) {
            CompositionLocalProvider(LocalReducedMotion provides true) { RollPresentationHost(queue) }
        }
        try {
            val observed = mutableSetOf<String>()
            val start = System.nanoTime()
            while (queue.pending.isNotEmpty() && System.nanoTime() - start < 5_000_000_000L) {
                scene.render(System.nanoTime()).close()
                observed += texts(scene).filter { it.startsWith("Итог ") }
                if (System.nanoTime() - start < 1_000_000_000L) assertEquals(2, queue.pending.size)
                Thread.sleep(12)
            }
            assertTrue(queue.pending.isEmpty())
            assertEquals(setOf("Итог 8", "Итог 13"), observed)
        } finally { scene.close() }
    }
}
