package ru.markn.dnd.network

import com.russhwolf.settings.Settings

/** Private device connection identity; never stores a room/adventure snapshot or emits diagnostics. */
internal class SettingsResumeSessionStore(private val settings: Settings) : ResumeSessionStore {
    override fun load(): ResumeTicket? = settings.getStringOrNull(KEY)?.let {
        GameWireJson.decodeFromString(ResumeTicket.serializer(), it)
    }
    override fun save(ticket: ResumeTicket) {
        settings.putString(KEY, GameWireJson.encodeToString(ResumeTicket.serializer(), ticket))
    }
    override fun clear() { settings.remove(KEY) }
    private companion object { const val KEY = "network.private.resumeTicket.v1" }
}

/** Keep storage failures observable to NetworkGameClient instead of claiming persistence. */
internal class UnavailableResumeSessionStore : ResumeSessionStore {
    override fun load(): ResumeTicket? = error("Private connection storage unavailable")
    override fun save(ticket: ResumeTicket): Unit = error("Private connection storage unavailable")
    override fun clear(): Unit = error("Private connection storage unavailable")
}
