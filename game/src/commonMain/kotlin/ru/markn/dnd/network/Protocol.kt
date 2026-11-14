package ru.markn.dnd.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.*

/** Change when making incompatible protocol changes. */
const val PROTOCOL_VERSION = 6
val GameWireJson = Json { ignoreUnknownKeys = true }

@Serializable
data class RoomMember(val player: Player, val online: Boolean)

@Serializable
data class RoomView(
    val code: String,
    val hostPlayerId: String,
    val members: List<RoomMember>,
    val adventure: AdventureState? = null,
    val revision: Long = 0,
    val gameMasterEnabled: Boolean = false,
    val pendingNarration: PendingNarration? = null,
    val gameMasterError: String? = null,
    val scenarioId: String = Lighthouse.ID,
)

@Serializable
data class PendingNarration(val id: String, val playerId: String, val turnId: TurnId,
    val kind: NarrationKind = NarrationKind.ACTION)

@Serializable
enum class NarrationKind { ACTION, REACTION, EPILOGUE }

@Serializable
sealed interface ClientMessage {
    @Serializable
    data class Join(
        val name: String,
        val roomCode: String = "",
        val resumeToken: String? = null,
        val version: Int = PROTOCOL_VERSION,
    ) : ClientMessage

    @Serializable
    data class Command(
        val requestId: Long,
        val action: NetworkAction,
        val turnId: TurnId? = null,
        val checkId: CheckId? = null,
        val text: String = "",
        val sessionId: Long? = null,
        val targetPlayerId: String? = null,
        val narrationId: String? = null,
        val scenarioId: String? = null,
    ) : ClientMessage
}

@Serializable
enum class NetworkAction { START, SUBMIT, ROLL, CANCEL, PASS, RESTART, SKIP_DISCONNECTED, REMOVE_DISCONNECTED, CANCEL_NARRATION, SELECT_SCENARIO }

@Serializable
sealed interface ServerMessage {
    /** Private: never broadcast a resume token to the room. */
    @Serializable
    data class Welcome(val playerId: String, val resumeToken: String, val room: RoomView) : ServerMessage
    @Serializable
    data class Snapshot(val room: RoomView) : ServerMessage
    @Serializable
    data class Reply(val requestId: Long, val error: String? = null, val room: RoomView) : ServerMessage
    @Serializable
    data class Error(val message: String) : ServerMessage
}
