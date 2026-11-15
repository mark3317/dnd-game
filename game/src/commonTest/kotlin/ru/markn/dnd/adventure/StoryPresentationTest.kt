package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.network.GameWireJson

class StoryPresentationTest {
    private val engine = AdventureEngine(DiceRoller { 1 })

    @Test fun npcRepliesHaveTheirOwnIdentityAndNeverBecomeNarratorSpeech() {
        val start = engine.newGame(scenarioId = ArchiveDemo.ID)
        val reply = engine.submit(start, start.activePlayer.id, start.turnId, "Агата, что происходит?",
            GameMasterDecision("TALK", "Вода добралась до сундука. Помогите спасти грамоты!", nextPlayerId = start.activePlayer.id, speakerNpcId = "ARCHIVIST"))
        val spoken = assertNotNull(reply.lastSpokenEntry)
        assertEquals(EntryKind.NPC, spoken.kind)
        assertEquals("ARCHIVIST", spoken.speakerNpcId)
        assertEquals("Агата", reply.speakerName(spoken))
        assertEquals("Вода добралась до сундука. Помогите спасти грамоты!", spoken.text)
        assertEquals(setOf("ARCHIVIST"), reply.scene.metNpcs[start.activePlayer.id])
        assertEquals(reply, GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(reply)))
    }

    @Test fun narratorAndNpcCannotImpersonateAbsentNpcOrSpeakOverMechanics() {
        val state = engine.newGame(scenarioId = ArchiveDemo.ID)
        val forged = GameMasterDecision(null, "Я курьер", speakerNpcId = "COURIER")
        assertEquals(state, engine.submit(state, state.activePlayer.id, state.turnId, "Привет", forged))
        val mechanical = GameMasterDecision("DEFEND", "Бейте их", speakerNpcId = "ARCHIVIST")
        assertEquals(state, engine.submit(state, state.activePlayer.id, state.turnId, "Обороняемся", mechanical))
    }

    @Test fun systemCheckAndTurnMessagesNeverReplaceSpokenSubtitles() {
        val state = engine.newGame()
        val pending = engine.submit(state, state.activePlayer.id, state.turnId, Lighthouse.CLIMB.text)
        assertTrue(pending.journal.any { it.kind == EntryKind.SYSTEM && it.text.contains("Предложена проверка") })
        assertEquals(EntryKind.NARRATOR, pending.lastSpokenEntry?.kind)
        val cancelled = engine.cancelCheck(pending, pending.activePlayer.id, pending.pendingCheck!!.id)
        assertEquals(pending.lastSpokenEntry, cancelled.lastSpokenEntry)
        val passed = engine.endTurn(cancelled, cancelled.activePlayer.id, cancelled.turnId)
        assertEquals(EntryKind.NARRATOR, passed.lastSpokenEntry?.kind)
        assertTrue(passed.lastSpokenEntry!!.text.contains("передаёт слово"))
        assertFalse(JournalEntry(9, EntryKind.CHECK, "d20", null).isSpoken)
    }

    @Test fun scriptedDialogueAlsoSpeaksAsNpcAndAuthoredStoriesAvoidRulesInSpeech() {
        for (scenario in Scenarios.all) {
            val state = engine.newGame(scenarioId = scenario.id)
            val talk = scenario.actions.first { it.narrative == NarrativeKind.CONVERSATION }
            val next = engine.submit(state, state.activePlayer.id, state.turnId, talk.text)
            assertEquals(EntryKind.NPC, next.lastSpokenEntry!!.kind)
            val technical = Regex("d20|модификатор|минимальная сложность|профили героев|здоровье не ниже", RegexOption.IGNORE_CASE)
            val speech = listOf(scenario.introduction) + scenario.sites.values.map { it.conversation } +
                scenario.facts.values.map { it.description } + scenario.endings.values.map { it.description }
            assertFalse(speech.any { technical.containsMatchIn(it) }, scenario.id)
        }
    }
}
