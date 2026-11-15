package ru.markn.dnd.adventure.ui

import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import kotlin.test.*

class AdventureDialogLayoutTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun longDialogKeepsBothActionsVisibleOnNarrowAndShortSurfaces() {
        for ((width, height) in listOf(320 to 640, 320 to 360)) {
            var confirmed = 0
            var dismissed = 0
            val scene = ImageComposeScene(width, height) {
                AdventureTheme {
                    AdventureDialogContent("Вернуться в меню?",
                        text = { Text("Длинное пояснение с сохранением состояния. ".repeat(80)) },
                        confirmButton = { AdventureButton(onClick = { confirmed++ }) { Text("В меню") } },
                        dismissButton = { AdventureButton(onClick = { dismissed++ }) { Text("Продолжить игру") } })
                }
            }
            try {
                repeat(3) { scene.render(it * 100_000_000L).close() }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val all = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                val actions = all.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                assertEquals(2, actions.size)
                for (action in actions) {
                    val bounds = action.boundsInRoot
                    assertTrue(bounds.top >= 0 && bounds.bottom <= height, "Action is outside viewport: $bounds")
                    assertTrue(bounds.left >= 0 && bounds.right <= width, "Action overflows width: $bounds")
                    assertTrue(action.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                }
                assertEquals(1, confirmed)
                assertEquals(1, dismissed)
                assertTrue(all.any { it.config.getOrNull(SemanticsActions.ScrollBy) != null }, "Long body must scroll independently")
            } finally { scene.close() }
        }
    }
}
