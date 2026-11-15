package ru.markn.dnd.combat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import ru.markn.dnd.adventure.ui.LocalGameMotion
import ru.markn.dnd.adventure.ui.PresentedRoll
import ru.markn.dnd.adventure.ui.RollPresentationHost
import ru.markn.dnd.adventure.ui.RollPresentationQueue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CombatPresentationTest {
    @Test fun doubleTapMovementUsesCurrentCommandAvailability() {
        val battle = CombatDemo()
        checkCommandAvailability(battle, DemoAction.MOVE,
            CombatDemo.tiles.first { battle.preview(DemoAction.MOVE, it).valid })
    }

    @Test fun doubleTapAttackUsesCurrentCommandAvailability() {
        val battle = CombatDemo().let { state -> state.copy(activeId = "bran",
            fighters = state.fighters.map { if (it.id == "bran") it.copy(tile = Tile(3, 1)) else it }) }
        checkCommandAvailability(battle, DemoAction.ATTACK, Tile(4, 1))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun checkCommandAvailability(battle: CombatDemo, expectedAction: DemoAction, destination: Tile) {
        var enabled by mutableStateOf(false)
        var moves = 0
        val scene = ImageComposeScene(1000, 800) {
            CombatPrototypeScreen(onBack = {}, battleState = battle, commandsEnabled = enabled,
                onCommand = { action, tile ->
                    assertEquals(expectedAction, action)
                    assertEquals(destination, tile)
                    moves++
                })
        }
        try {
            var frame = 0L
            fun settle() { repeat(5) { scene.render(frame).close(); frame += 100_000_000L } }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            settle()
            enabled = true
            settle()
            val cell = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith(destination.label) } == true
            }
            val point = cell.boundsInRoot.center
            fun doubleTap(at: Long) {
                repeat(2) { index ->
                    scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = at + index * 100)
                    scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = at + 20 + index * 100)
                    settle()
                }
            }
            doubleTap(1000L)
            val texts = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
            assertEquals(1, moves, "A completed presentation must allow the command. Text: $texts")
            enabled = false
            settle()
            doubleTap(2000L)
            assertEquals(1, moves, "A new presentation must block commands again")
            enabled = true
            settle()
            doubleTap(3000L)
            assertEquals(2, moves, "The next completed presentation must unlock the command again")
        } finally { scene.close() }
    }

    @Test fun movementReleasesCommandLockAfterExit() {
        val battle = CombatDemo()
        val event = CombatEvent(CombatEventKind.MOVE, battle.active, title = "Движение", logIndex = 0,
            fighters = battle.fighters, path = listOf(battle.active.tile, Tile(2, 2)))
        val queue = RollPresentationQueue(emptyList()).apply { receive(listOf(PresentedRoll("move", combat = event))) }
        val scene = ImageComposeScene(100, 100) {
            CompositionLocalProvider(LocalGameMotion provides true) { RollPresentationHost(queue) }
        }
        try {
            val started = System.nanoTime()
            while (queue.pending.isNotEmpty() && System.nanoTime() - started < 5_000_000_000L) {
                scene.render(System.nanoTime()).close()
                Thread.sleep(16)
            }
            assertTrue(queue.pending.isEmpty(), "The finished movement must leave the presentation queue")
        } finally { scene.close() }
    }
}
