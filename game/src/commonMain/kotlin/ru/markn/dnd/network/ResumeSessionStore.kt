package ru.markn.dnd.network

import io.ktor.http.Url
import kotlinx.serialization.Serializable

/** Private connection identity only. Never a game save, public room field or log payload. */
@Serializable
data class ResumeTicket(val endpoint: String, val playerName: String, val roomCode: String, val token: String) {
    override fun toString(): String = "ResumeTicket(<private>)"

    internal fun usable(): Boolean = runCatching {
        val url = Url(endpoint)
        url.protocol.name in setOf("ws", "wss") && url.host.isNotBlank() &&
            url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty() &&
            url.encodedPath == "/play" && playerName.isNotBlank() && roomCode.isNotBlank() && token.isNotBlank()
    }.getOrDefault(false)
}

/** Platform adapters keep this private on the device. Failures must not affect the server party. */
interface ResumeSessionStore {
    fun load(): ResumeTicket?
    fun save(ticket: ResumeTicket)
    fun clear()
}

/** Default for callers without a persistent platform adapter: RAM recovery still works. */
object NoopResumeSessionStore : ResumeSessionStore {
    override fun load(): ResumeTicket? = null
    override fun save(ticket: ResumeTicket) = Unit
    override fun clear() = Unit
}
