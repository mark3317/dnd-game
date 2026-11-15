package ru.markn.dnd.adventure

import kotlin.test.*

class InputPresentationTest {
    private val engine = AdventureEngine()

    @Test fun mixedInputUsesNarratorThenHeroWithoutReadingRawInputOrRepeatingPreview() {
        val start = engine.newGame().schedulePlayback(null, 1000)
        val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин собирается подойти к хранителю."),
            InputUtterance(InputUtteranceKind.SPEECH, "Я могу помочь. Что случилось?"))
        val now = start.playback.busyUntil
        val preview = start.previewInputPresentation(parts, now)
        assertEquals(start.players, preview.players)
        assertEquals(start.journal, preview.journal)
        assertEquals(listOf("narrator", "rin"), preview.playback.speeches.map { preview.speechVoice(it.entry) })
        val raw = "Подхожу к хранителю и говорю: я могу помочь. Что случилось?"
        val result = engine.submit(preview, start.activePlayer.id, start.turnId, raw,
            GameMasterDecision(null, "Помогите с механизмом.", speakerNpcId = "KEEPER", inputPresentation = parts))
            .schedulePlayback(preview, now)
        assertEquals(listOf("narrator", "rin", "narrator"), result.playback.speeches.map { result.speechVoice(it.entry) })
        assertEquals(listOf(EntryKind.NARRATOR, EntryKind.PLAYER, EntryKind.NPC), result.playback.speeches.map { it.entry.kind })
        assertFalse(result.journal.first { it.text == raw }.isSpoken)
        assertEquals(1, result.playback.speeches.count { it.entry.text == parts[0].text })
        assertEquals(1, result.playback.speeches.count { it.entry.text == parts[1].text })
        assertNull(result.playback.previewEntryId)
        assertTrue(result.playback.previewEntryIds.isEmpty())
    }

    @Test fun actionNarrationDoesNotCommitSuccessOrSpendChecksBeforeRoll() {
        val start = engine.newGame()
        val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин пытается подняться наверх."))
        val result = engine.submit(start, start.activePlayer.id, start.turnId, "Поднимаюсь наверх",
            GameMasterDecision(Lighthouse.CLIMB.id, "Не используется", inputPresentation = parts))
        assertNotNull(result.pendingCheck)
        assertEquals(start.players, result.players)
        assertEquals(start.scene, result.scene)
        assertEquals(start.checksRemaining, result.checksRemaining)
        assertTrue(result.journal.filter { it.isSpoken }.none { it.kind == EntryKind.PLAYER })
        assertEquals(parts.single().text, result.lastSpokenEntry!!.text)
    }

    @Test fun rejectedPlanDoesNotCommitPresentation() {
        val start = engine.newGame()
        val plan = SceneActionPlan("Невозможный переход", null, null,
            listOf(SceneEffect.Move("MISSING")), emptyList())
        assertEquals(start, engine.submit(start, start.activePlayer.id, start.turnId, "Иду туда",
            GameMasterDecision(null, "Намерение", plan, inputPresentation = listOf(
                InputUtterance(InputUtteranceKind.ACTION, "Рин собирается уйти.")))))
    }
}
