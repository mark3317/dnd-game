package ru.markn.dnd.network

import com.russhwolf.settings.MapSettings
import kotlin.test.*

class SettingsResumeSessionStoreTest {
    @Test fun privateIdentitySurvivesAdapterRestartAndClearsWithoutSavingGameState() {
        val settings = MapSettings()
        val ticket = ResumeTicket("wss://example.invalid/play", "Игрок", "ABC123", "private-secret")
        val store = SettingsResumeSessionStore(settings)
        assertNull(store.load())
        store.save(ticket)
        assertEquals(ticket, SettingsResumeSessionStore(settings).load())
        assertEquals(setOf("network.private.resumeTicket.v1"), settings.keys)
        assertFalse(ticket.toString().contains(ticket.token))
        store.clear()
        assertNull(SettingsResumeSessionStore(settings).load())
    }

    @Test fun corruptOrUnavailableStorageIsReportedToClientInsteadOfClaimingSuccess() {
        val settings = MapSettings().apply { putString("network.private.resumeTicket.v1", "broken") }
        assertFails { SettingsResumeSessionStore(settings).load() }
        val unavailable = UnavailableResumeSessionStore()
        assertFails { unavailable.load() }
        assertFails { unavailable.save(ResumeTicket("wss://example.invalid/play", "Игрок", "ABC123", "secret")) }
        assertFails { unavailable.clear() }
    }
}
