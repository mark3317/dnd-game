package ru.markn.dnd.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.*

/** Change when making incompatible protocol changes. */
const val PROTOCOL_VERSION = 16
val GameWireJson = Json { ignoreUnknownKeys = true }

@Serializable
data class RoomMember(val player: Player, val online: Boolean, val characterConfirmed: Boolean = false)

@Serializable
enum class RoomLifecycle { LOBBY, ACTIVE, PAUSED, COMPLETED, CLOSED }

@Serializable
enum class ConnectionRejection { ROOM_UNAVAILABLE, RESUME_REJECTED, VERSION_MISMATCH }

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
    val serverTimeMillis: Long = 0,
    val presentation: RoomPresentation? = null,
    val lifecycle: RoomLifecycle = RoomLifecycle.LOBBY,
    val waitingPlayerIds: List<String> = emptyList(),
    val pausedAtMillis: Long? = null,
    val closedByPlayerId: String? = null,
)

@Serializable
data class PendingNarration(val id: String, val playerId: String, val turnId: TurnId,
    val kind: NarrationKind = NarrationKind.ACTION)

@Serializable
enum class NarrationKind { ACTION, REACTION, EPILOGUE }

@Serializable
sealed interface ClientMessage {
    /** Explicit confirmed departure; closing the transport alone is an accidental disconnect. */
    @Serializable
    data object Leave : ClientMessage

    /** Presentation transport is independent of gameplay request IDs and command busy state. */
    @Serializable
    data class SpeechAck(val acknowledgement: RoomSpeechAcknowledgement) : ClientMessage

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
        val character: CharacterProfile? = null,
        val useRacialTalent: Boolean = false,
        val combatAction: ru.markn.dnd.combat.DemoAction? = null,
        val combatTarget: ru.markn.dnd.combat.Tile? = null,
    ) : ClientMessage
}

@Serializable
enum class NetworkAction { START, SUBMIT, ROLL, CANCEL, PASS, RESTART, SKIP_DISCONNECTED, REMOVE_DISCONNECTED, CANCEL_NARRATION, SELECT_SCENARIO, SELECT_CHARACTER, COMBAT }

@Serializable
sealed interface ServerMessage {
    /** Private acknowledgement of explicit departure, including departure from the lobby. */
    @Serializable
    data object Left : ServerMessage

    /** Private: never broadcast a resume token to the room. */
    @Serializable
    data class Welcome(val playerId: String, val resumeToken: String, val room: RoomView) : ServerMessage
    @Serializable
    data class Snapshot(val room: RoomView) : ServerMessage
    @Serializable
    data class Reply(val requestId: Long, val error: String? = null, val room: RoomView) : ServerMessage
    @Serializable
    data class Error(val message: String, val reason: ConnectionRejection? = null) : ServerMessage
}
