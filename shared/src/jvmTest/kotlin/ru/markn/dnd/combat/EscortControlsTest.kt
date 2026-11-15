package ru.markn.dnd.combat

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.*

class EscortControlsTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun cartSelectionOnlyExposesAnExplicitAdvanceButton() {
        val initial = CombatDemo(activeId = "mira", escortObjective = EscortObjectiveState(
            EscortObjectiveDefinition((0..5).map { Tile(it, 4) })))
        val battle = initial.copy(fighters = initial.fighters.map { if (it.id == "rin") it.copy(tile = Tile(2, 4)) else it })
        assertTrue(battle.preview(DemoAction.ESCORT, Tile(0, 4)).valid)
        val commands = mutableListOf<Pair<DemoAction?, Tile?>>()
        val scene = ImageComposeScene(1000, 1000) {
            CombatPrototypeScreen(onBack = {}, battleState = battle, onCommand = { action, tile -> commands += action to tile })
        }
        try {
            var frame = 0L
            fun settle() { repeat(5) { scene.render(frame).close(); frame += 100_000_000L } }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
            settle()
            val cart = allNodes().first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Повозка, A5") } == true
            }
            repeat(2) { assertTrue(cart.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true); settle() }
            assertTrue(commands.isEmpty(), "Selecting/tapping the neutral cart must not spend an action")
            val advance = allNodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any { descendant ->
                    descendant.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Продвинуть повозку" } == true
                }
            }
            assertNull(advance.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(advance.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            assertEquals(1, commands.size)
            assertEquals(DemoAction.ESCORT, commands.single().first)
            assertEquals(Tile(0, 4), commands.single().second)
        } finally { scene.close() }
    }
}
