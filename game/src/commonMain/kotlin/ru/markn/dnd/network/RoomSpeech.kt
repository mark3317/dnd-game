package ru.markn.dnd.network

import kotlinx.serialization.Serializable
import ru.markn.dnd.adventure.JournalEntry
import ru.markn.dnd.adventure.SUBTITLE_CHARACTER_MILLIS

/** Sentence timing without a repeated trailing pause; the authority adds one batch reading allowance. */
fun roomSpeechTextDuration(text: String): Long = maxOf(600L, text.length.toLong() * SUBTITLE_CHARACTER_MILLIS)

@Serializable
enum class RoomSpeechKind { OPENING, DIALOGUE, ENDING }

@Serializable
enum class RoomSpeechSource { TEXT, PREPARED, GENERATED }

@Serializable
enum class RoomPresentationPhase { PREPARING, PLAYING, COMPLETE }

/** Stable across preview -> confirmed journal, reconnect and timing updates.
 * audioId is a bundled filename for PREPARED, a worker hash for GENERATED, never a URL.
 * Null startsAt means preparation/readiness is still pending, not a cue at time zero.
 * TEXT is final fallback; GENERATED with null audioId means synthesis is still pending.
 */
@Serializable
data class RoomSpeechCue(
    val id: String,
    val kind: RoomSpeechKind,
    val entry: JournalEntry,
    val voice: String,
    val segmentIndex: Int = 0,
    val shotIndex: Int? = null,
    val source: RoomSpeechSource = RoomSpeechSource.TEXT,
    val audioId: String? = null,
    val durationMillis: Long = 0,
    val startsAt: Long? = null,
    val readUntil: Long? = null,
    val deadlineAt: Long? = null,
)

/** An authority-owned batch: individual completion ACKs do not insert an RTT between sentences.
 * Sealed means no more streamed intentions/response fragments will be appended to this batch.
 * COMPLETE is retained in snapshots so reconnecting clients can distinguish a finished epilogue.
 */
@Serializable
data class RoomPresentation(
    val sessionId: Long,
    val batchId: String,
    val phase: RoomPresentationPhase = RoomPresentationPhase.PREPARING,
    val cues: List<RoomSpeechCue> = emptyList(),
    val sealed: Boolean = true,
    val hardDeadlineAt: Long = 0,
) {
    val busy: Boolean get() = phase != RoomPresentationPhase.COMPLETE

    /** Retain the last frame while audio finishes; never select an unscheduled future frame. */
    fun currentCue(now: Long): RoomSpeechCue? {
        if (!busy) return null
        return cues.lastOrNull { it.startsAt?.let { start -> start <= now } == true }
            ?: cues.firstOrNull()
    }
}

@Serializable
enum class RoomSpeechAckStatus { READY, FINISHED, FAILED }

@Serializable
enum class RoomSpeechMode { AUDIO, TEXT }

@Serializable
enum class RoomSpeechFailure { PLAYBACK_UNAVAILABLE, USER_MUTED, RESUMED_AS_TEXT }

/** No client timestamps, dice, player IDs or exception text. Sender identity comes from the socket. */
@Serializable
data class RoomSpeechAcknowledgement(
    val sessionId: Long,
    val batchId: String,
    val cueId: String,
    val status: RoomSpeechAckStatus,
    val mode: RoomSpeechMode,
    val reason: RoomSpeechFailure? = null,
)
