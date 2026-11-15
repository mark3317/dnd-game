package ru.markn.dnd.server

import java.util.UUID
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

internal data class RoomSpeechPreparation(val batchId: String, val cueId: String, val text: String, val voice: String)
data class RoomSpeechAudio(val id: String, val durationMillis: Long)

/** Pure authority state machine. Caller serializes all methods under Rooms.mutex; no IO here. */
internal class RoomSpeechCoordinator(
    private val preparationMillis: Long = 15_000,
    private val readinessMillis: Long = 3_000,
    private val finishGraceMillis: Long = 8_000,
    private val hardLimitMillis: Long = 180_000,
    private val endingReadinessMillis: Long = 30_000,
) {
    var view: RoomPresentation? = null
        private set
    private var observed: AdventureState? = null
    private var pendingObserved = false
    private val roster = mutableSetOf<String>()
    private val ready = mutableMapOf<String, MutableMap<String, RoomSpeechMode>>()
    private val done = mutableMapOf<String, MutableSet<String>>()
    private val preparationDeadlines = mutableMapOf<String, Long>()
    private val readyDeadlines = mutableMapOf<String, Long>()
    private val claimed = mutableSetOf<String>()
    private data class RecoveryGate(val cueId: String, val deadline: Long)
    private val recovery = mutableMapOf<String, RecoveryGate>()

    /** Keep roster/timing intact during a room pause, but let a new player instance choose TEXT. */
    fun forgetParticipantAcknowledgements(playerId: String) {
        ready.values.forEach { it.remove(playerId) }
        done.values.forEach { it.remove(playerId) }
        recovery.remove(playerId)
    }

    /** A resumed client must first receive Welcome before it can report its playback mode. */
    fun awaitParticipantRecovery(playerId: String, now: Long) {
        val current = view ?: return
        if (!current.busy || playerId !in roster) return
        val cue = current.currentCue(now) ?: return
        recovery[playerId] = RecoveryGate(cue.id, now + readinessMillis)
    }

    fun shiftTimeline(delta: Long) {
        if (delta <= 0) return
        view = view?.let { current -> current.copy(
            hardDeadlineAt = current.hardDeadlineAt + delta,
            cues = current.cues.map { cue -> cue.copy(startsAt = cue.startsAt?.plus(delta),
                readUntil = cue.readUntil?.plus(delta), deadlineAt = cue.deadlineAt?.plus(delta)) }) }
        preparationDeadlines.keys.toList().forEach { preparationDeadlines[it] = preparationDeadlines.getValue(it) + delta }
        readyDeadlines.keys.toList().forEach { readyDeadlines[it] = readyDeadlines.getValue(it) + delta }
        recovery.keys.toList().forEach { id -> recovery[id] = recovery.getValue(id).let { it.copy(deadline = it.deadline + delta) } }
    }

    fun closePresentation() {
        view = view?.copy(phase = RoomPresentationPhase.COMPLETE, sealed = true)
        recovery.clear()
    }

    fun observe(state: AdventureState, online: Set<String>, pendingAi: Boolean, now: Long) {
        val before = observed
        if (before == null || before.sessionId != state.sessionId) {
            begin(state.sessionId, online, now)
            val opening = state.openingShots().mapIndexed { index, shot ->
                cue("opening:$index", RoomSpeechKind.OPENING, JournalEntry(-1L - index, EntryKind.NARRATOR, shot.text),
                    "narrator", index, 0, now)
            }
            append(opening)
        } else {
            val added = state.journal.filter { it.id > (before.journal.lastOrNull()?.id ?: 0) && it.isSpoken }
            val newEnding = before.ending == null && state.ending != null
            val preview = state.playback.speeches.filter { it.entry.id in state.playback.previewEntryIds }
                .groupBy { it.entry.id }.values.map { parts -> parts.first().entry.copy(text = parts.joinToString(" ") { it.entry.text }) }
            val entries = (preview + added.filterNot { newEnding && it.kind == EntryKind.NARRATOR && it.text == state.outcome?.description })
            if ((entries.isNotEmpty() || newEnding || (pendingAi && !pendingObserved)) && (view == null || view?.busy == false))
                begin(state.sessionId, online, now)
            val cues = entries.flatMap { entry -> speechSegments(entry.text).mapIndexed { index, text ->
                cue("entry:${entry.id}:$index", if (state.ending != null) RoomSpeechKind.ENDING else RoomSpeechKind.DIALOGUE, entry.copy(text = text), state.speechVoice(entry), null, index, now)
            } }.toMutableList()
            if (newEnding) cues += state.endingShots().mapIndexed { index, shot ->
                cue("ending:$index", RoomSpeechKind.ENDING, JournalEntry(-1000L - index, EntryKind.NARRATOR, shot.text), "narrator", index, 0, now)
            }
            if (cues.isNotEmpty() || pendingAi) {
                append(cues)
            }
        }
        observed = state
        pendingObserved = pendingAi
        roster.retainAll(online) // Reconnect never adds a new completion dependency to a live batch.
        view = view?.let { if (it.busy) it.copy(sealed = !pendingAi) else it }
        tick(now)
    }

    private fun begin(session: Long, online: Set<String>, now: Long) {
        roster.clear(); roster += online
        ready.clear(); done.clear(); claimed.clear(); preparationDeadlines.clear(); readyDeadlines.clear(); recovery.clear()
        view = RoomPresentation(session, UUID.randomUUID().toString(), hardDeadlineAt = now + hardLimitMillis)
    }

    private fun cue(id: String, kind: RoomSpeechKind, entry: JournalEntry, voice: String,
        shot: Int?, segment: Int, now: Long): RoomSpeechCue {
        val prepared = preparedSpeechAsset(entry.text, voice)
        val existing = view?.cues?.firstOrNull { it.id == id }
        if (existing != null) return existing
        // Ending READY also means the client's confirmed final combat/dice insertions drained.
        // Synthesis proceeds concurrently; its completion must not extend this bounded gate.
        if (kind == RoomSpeechKind.ENDING) readyDeadlines[id] = now + endingReadinessMillis
        else if (prepared != null) readyDeadlines[id] = now + readinessMillis
        return RoomSpeechCue(id, kind, entry, voice, segment, shot,
            source = if (prepared != null) RoomSpeechSource.PREPARED else RoomSpeechSource.GENERATED,
            audioId = prepared?.file, durationMillis = prepared?.duration_ms ?: 0)
    }

    private fun append(cues: List<RoomSpeechCue>) {
        val current = view ?: return
        val known = current.cues.map { it.id }.toSet()
        view = current.copy(cues = current.cues + cues.filter { it.id !in known }.distinctBy { it.id })
    }

    fun takePreparations(now: Long): List<RoomSpeechPreparation> {
        val current = view ?: return emptyList()
        if (!current.busy) return emptyList()
        val pending = current.cues.filter { it.source == RoomSpeechSource.GENERATED && it.audioId == null }
        val capacity = (2 - pending.count { it.id in claimed }).coerceAtLeast(0)
        return pending.filter { it.id !in claimed }.take(capacity).map {
            claimed += it.id
            preparationDeadlines[it.id] = now + preparationMillis
            RoomSpeechPreparation(current.batchId, it.id, it.entry.text, it.voice)
        }
    }

    fun prepared(batchId: String, cueId: String, audio: RoomSpeechAudio?, now: Long): Boolean {
        val current = view ?: return false
        if (!current.busy || current.batchId != batchId) return false
        val cue = current.cues.firstOrNull { it.id == cueId } ?: return false
        if (cue.source != RoomSpeechSource.GENERATED || cue.audioId != null || cue.startsAt != null) return false
        val deadline = preparationDeadlines[cueId] ?: return false
        val valid = audio?.takeIf { now < deadline && it.id.matches(Regex("[a-f0-9]{64}")) && it.durationMillis in 1..120_000 }
        val updated = if (valid == null) cue.copy(source = RoomSpeechSource.TEXT)
            else cue.copy(audioId = valid.id, durationMillis = valid.durationMillis)
        if (cue.kind != RoomSpeechKind.ENDING) readyDeadlines[cueId] = now + readinessMillis
        view = current.copy(cues = current.cues.map { if (it.id == cueId) updated else it })
        tick(now)
        return true
    }

    fun acknowledge(playerId: String, ack: RoomSpeechAcknowledgement, now: Long): Boolean {
        val current = view ?: return false
        if (!current.busy || current.sessionId != ack.sessionId || current.batchId != ack.batchId || playerId !in roster) return false
        val cue = current.cues.firstOrNull { it.id == ack.cueId } ?: return false
        val modes = ready.getOrPut(cue.id) { mutableMapOf() }
        when (ack.status) {
            RoomSpeechAckStatus.READY -> {
                if (playerId in modes) return false
                if (ack.mode == RoomSpeechMode.AUDIO && (cue.source == RoomSpeechSource.TEXT || cue.audioId == null)) return false
                modes[playerId] = ack.mode
            }
            RoomSpeechAckStatus.FINISHED -> {
                val startsAt = cue.startsAt
                if (startsAt == null || modes[playerId] != ack.mode || now < startsAt) return false
                if (!done.getOrPut(cue.id) { mutableSetOf() }.add(playerId)) return false
            }
            RoomSpeechAckStatus.FAILED -> {
                if (!done.getOrPut(cue.id) { mutableSetOf() }.add(playerId)) return false
                modes[playerId] = RoomSpeechMode.TEXT
            }
        }
        tick(now)
        return true
    }

    fun discardUnstartedPreview(ids: Set<Long>, now: Long) {
        val current = view ?: return
        view = current.copy(cues = current.cues.filterNot {
            val startsAt = it.startsAt
            it.entry.id in ids && (startsAt == null || startsAt > now)
        })
    }

    fun tick(now: Long) {
        var current = view ?: return
        if (!current.busy) return
        if (now >= current.hardDeadlineAt) {
            view = current.copy(phase = RoomPresentationPhase.COMPLETE, sealed = true)
            return
        }
        current = current.copy(cues = current.cues.map { cue ->
            val allText = roster.isNotEmpty() && roster.all { ready[cue.id]?.get(it) == RoomSpeechMode.TEXT }
            if (cue.source == RoomSpeechSource.GENERATED && cue.audioId == null &&
                (allText || preparationDeadlines[cue.id]?.let { now >= it } == true)) cue.copy(source = RoomSpeechSource.TEXT) else cue
        })
        val scheduled = current.cues.toMutableList()
        for (index in scheduled.indices) {
            val cue = scheduled[index]
            if (cue.startsAt != null) continue
            if (cue.source == RoomSpeechSource.GENERATED && cue.audioId == null) break
            val previous = scheduled.getOrNull(index - 1)
            if (index > 0 && previous?.readUntil == null) break
            // A streamed dialogue batch can gain its ending before final visual insertions drain.
            // Gate once at the kind boundary, not once per ending sentence.
            val entersEnding = cue.kind == RoomSpeechKind.ENDING && previous?.kind != RoomSpeechKind.ENDING
            if (index == 0 || entersEnding) {
                val allReady = roster.all { it in ready[cue.id].orEmpty() }
                if ((cue.source != RoomSpeechSource.TEXT || cue.kind == RoomSpeechKind.ENDING) &&
                    !allReady && now < (readyDeadlines[cue.id] ?: Long.MAX_VALUE)) break
            }
            // One lead-in per batch. Subsequent fragments have no ACK/round-trip barrier.
            val start = previous?.readUntil?.coerceAtLeast(now) ?: (now + 350)
            // Audio flows at actual WAV duration; do not add subtitle padding to every sentence.
            val duration = if (cue.source == RoomSpeechSource.TEXT) roomSpeechTextDuration(cue.entry.text) else cue.durationMillis
            val readUntil = start + duration
            scheduled[index] = cue.copy(startsAt = start, readUntil = readUntil, deadlineAt = readUntil + finishGraceMillis)
        }
        val hasStarted = scheduled.any { it.startsAt != null }
        val minimumReadUntil = minimumReadUntil(scheduled)
        val allRead = scheduled.all { it.readUntil?.let { end -> now >= end } == true } &&
            (minimumReadUntil == null || now >= minimumReadUntil)
        val batchFinishDeadline = scheduled.lastOrNull { it.readUntil != null }?.readUntil?.let { last ->
            maxOf(last, minimumReadUntil ?: last) + finishGraceMillis
        }
        val withDeadlines = scheduled.map { cue -> if (cue.startsAt == null) cue else cue.copy(
            deadlineAt = batchFinishDeadline?.coerceAtMost(current.hardDeadlineAt)) }
        val recoveryReady = recovery.all { (player, gate) -> player !in roster || now >= gate.deadline ||
            player in ready[gate.cueId].orEmpty() || player in done[gate.cueId].orEmpty() }
        val allFinished = recoveryReady && withDeadlines.all { cue ->
            batchFinishDeadline != null && (now >= batchFinishDeadline || roster.all { player ->
                player !in ready[cue.id].orEmpty() || player in done[cue.id].orEmpty()
            })
        }
        view = current.copy(cues = withDeadlines, phase = when {
            current.sealed && allRead && allFinished -> RoomPresentationPhase.COMPLETE
            hasStarted -> RoomPresentationPhase.PLAYING
            else -> RoomPresentationPhase.PREPARING
        })
    }

    /** Reading allowance is once per batch, never a gap inserted between audio fragments. */
    private fun minimumReadUntil(cues: List<RoomSpeechCue>): Long? = cues.firstOrNull()?.startsAt?.let { first ->
        first + cues.sumOf { it.entry.text.length.toLong() * SUBTITLE_CHARACTER_MILLIS } + 1_600
    }

    fun nextWakeAt(now: Long): Long? {
        val current = view ?: return null
        if (!current.busy) return null
        return (listOfNotNull(current.hardDeadlineAt, minimumReadUntil(current.cues)) + recovery.values.map { it.deadline } + current.cues.flatMap { cue -> listOfNotNull(
            if (cue.source == RoomSpeechSource.GENERATED && cue.audioId == null) preparationDeadlines[cue.id] else null,
            if (cue.startsAt == null && (cue.audioId != null || cue.kind == RoomSpeechKind.ENDING)) readyDeadlines[cue.id] else null,
            cue.readUntil, cue.deadlineAt,
        ) }).filter { it > now }.minOrNull()
    }
}
