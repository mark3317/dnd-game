package ru.markn.dnd.adventure

import kotlin.test.*

class SpeechTest {
    @Test fun voicesFollowSpeakersRatherThanTheActivePlayer() {
        val state = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID)
        assertEquals("narrator", state.speechVoice(JournalEntry(2, EntryKind.NARRATOR, "Рассказ")))
        assertEquals("archivist", state.speechVoice(JournalEntry(2, EntryKind.NPC, "Здравствуйте", speakerNpcId = "ARCHIVIST")))
        assertEquals("courier", state.speechVoice(JournalEntry(3, EntryKind.NPC, "В путь", speakerNpcId = "COURIER")))
        assertEquals("mira", state.speechVoice(JournalEntry(4, EntryKind.PLAYER, "Подождите", actorName = "Мира")))
        assertEquals("rin", state.speechVoice(JournalEntry(5, EntryKind.PLAYER, "Я посмотрю", actorName = "Рин")))
    }

    @Test fun speechUsesTheGameServerAndPreservesTlsAndPort() {
        assertEquals("https://example.org:9443", speechServerOrigin("wss://example.org:9443/play"))
        assertEquals("http://192.168.1.10:8080", speechServerOrigin("ws://192.168.1.10:8080"))
        assertFailsWith<IllegalArgumentException> { speechServerOrigin("file:///tmp/audio") }
    }
}
