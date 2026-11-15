package ru.markn.dnd.adventure

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlin.test.*
import ru.markn.dnd.adventure.ui.*

class AdventureControlsTest {
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun texts(node: SemanticsNode) = descendants(node).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty()
    }.map { it.text }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun narrowCheckPanelKeepsRollCancelAndLongTalentButtonsSeparateAndUsable() {
        val engine = AdventureEngine()
        val initial = engine.newGame().let { state -> state.copy(players = state.players.mapIndexed { i, player ->
            if (i == 0) player.copy(hero = player.hero.copy(profile = player.hero.profile!!.copy(race = CharacterRace.HALFLING))) else player
        }) }
        val state = engine.submit(initial, initial.activePlayer.id, initial.turnId, Lighthouse.CLIMB.text)
        val check = assertNotNull(state.pendingCheck)
        assertTrue(CharacterRules.canUseTalent(state, check.plan!!.check!!))
        val actions = mutableListOf<String>()
        val scene = ImageComposeScene(320, 1200) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                AdventureTheme { CheckPanel(check, state, { actions += "roll" }, { actions += "cancel" }, { actions += "talent" }) }
            }
        }
        try {
            repeat(6) { scene.render(it * 100_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            val buttons = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }.sortedBy { it.boundsInRoot.top }
            assertEquals(3, buttons.size)
            assertTrue(texts(buttons[2]).any { it.contains("бросить с +2") })
            for (button in buttons) {
                assertTrue(button.boundsInRoot.left >= 0f && button.boundsInRoot.right <= 320f)
                assertTrue(button.boundsInRoot.width >= 250f)
                assertTrue(button.boundsInRoot.height >= 48f)
            }
            assertTrue(buttons.zipWithNext().all { (a, b) -> a.boundsInRoot.bottom <= b.boundsInRoot.top })
            for (button in buttons) assertTrue(button.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            assertEquals(listOf("roll", "cancel", "talent"), actions)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun narrowComposerRetainsSendValidationAndDoesNotSqueezeTheEditableField() {
        val state = AdventureEngine().newGame()
        val submitted = mutableListOf<String>()
        var cleared: String? = null
        val scene = ImageComposeScene(320, 500) {
            AdventureTheme { ActionComposer(state, { submitted += it }, {}, "Осматриваю дверь", { cleared = it }, {}) }
        }
        try {
            repeat(6) { scene.render(it * 100_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            val field = nodes.first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            val send = nodes.first { it.config.getOrNull(SemanticsProperties.Role) == Role.Button && texts(it).contains("Отправить") }
            assertTrue(field.boundsInRoot.width >= 290f)
            assertTrue(field.boundsInRoot.bottom <= send.boundsInRoot.top)
            assertTrue(send.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            assertEquals(listOf("Осматриваю дверь"), submitted)
            assertEquals("", cleared)
        } finally { scene.close() }
    }
}
