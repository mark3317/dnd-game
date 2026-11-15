package ru.markn.dnd.adventure.ui

import ru.markn.dnd.network.*

internal data class RoomSpeechEventKey(val sessionId: Long, val batchId: String, val cueId: String, val connectionGeneration: Long)

/** Effects are executed by the player host; no clock, network or audio device lives here. */
internal sealed interface RoomSpeechEffect {
    data class Prepare(val cue: RoomSpeechCue, val key: RoomSpeechEventKey) : RoomSpeechEffect
    data class Play(val cue: RoomSpeechCue, val offsetMillis: Long, val key: RoomSpeechEventKey) : RoomSpeechEffect
    data object Stop : RoomSpeechEffect
    data class Ack(val value: RoomSpeechAcknowledgement) : RoomSpeechEffect
}

/** One sequential local queue under an authority-owned batch.
 * A delayed uninterrupted queue plays every sentence. Only joining an already playing
 * batch uses a recovery offset; unverified seek platforms recover as text instead.
 */
internal class RoomSpeechPlayback {
    private var presentation: RoomPresentation? = null
    private var generation = Long.MIN_VALUE
    private var joinedAt = 0L
    private var recoveredBatch = false
    private val modes = mutableMapOf<String, RoomSpeechMode>()
    private val preparing = mutableSetOf<String>()
    private val finished = mutableSetOf<String>()
    private val ignored = mutableSetOf<String>()
    private val readySent = mutableSetOf<String>()
    private val deferredFailures = mutableMapOf<String, RoomSpeechFailure>()
    private var endingReady = true
    private var activeId: String? = null
    private var displayed: RoomSpeechCue? = null
    var activeStartedAt: Long = 0
        private set
    var activeReadUntil: Long? = null
        private set

    val activeCue: RoomSpeechCue? get() = presentation?.cues?.firstOrNull { it.id == activeId }
    val displayedCue: RoomSpeechCue? get() = activeCue ?: displayed
    val activeMode: RoomSpeechMode? get() = activeId?.let(modes::get)

    fun update(
        value: RoomPresentation?, connectionGeneration: Long, connected: Boolean, now: Long,
        audioEnabled: Boolean, playerAvailable: Boolean, recoverySeekSupported: Boolean,
        endingReady: Boolean = true,
    ): List<RoomSpeechEffect> = buildList {
        if (!connected || value == null) {
            if (presentation != null) add(RoomSpeechEffect.Stop)
            resetPlayback()
            return@buildList
        }
        if (generation != connectionGeneration || presentation?.sessionId != value.sessionId || presentation?.batchId != value.batchId) {
            if (presentation != null) add(RoomSpeechEffect.Stop)
            resetPlayback()
            generation = connectionGeneration
            joinedAt = now
            recoveredBatch = value.phase == RoomPresentationPhase.PLAYING
            if (recoveredBatch) value.cues.filter { it.readUntil?.let { end -> end <= now } == true }
                .forEach { ignored += it.id }
        }
        presentation = value
        this@RoomSpeechPlayback.endingReady = endingReady
        if (!value.busy) {
            if (activeId != null) add(RoomSpeechEffect.Stop)
            activeId = null
            return@buildList
        }
        if (value.hardDeadlineAt > 0 && now >= value.hardDeadlineAt) {
            activeCue?.takeIf { activeMode == RoomSpeechMode.AUDIO }?.let { addAll(failed(key(it.id))) }
            ignored += value.cues.map { it.id }
            activeId = null
            return@buildList
        }
        for (cue in value.cues) {
            if (cue.id in finished || cue.id in ignored) continue
            val recovery = recoveredBatch && cue.startsAt?.let { it < joinedAt } == true
            val wantsText = !audioEnabled || !playerAvailable || cue.source == RoomSpeechSource.TEXT ||
                (recovery && !recoverySeekSupported)
            if (modes[cue.id] == RoomSpeechMode.AUDIO && wantsText) {
                addAll(failed(key(cue.id), if (!audioEnabled) RoomSpeechFailure.USER_MUTED else RoomSpeechFailure.PLAYBACK_UNAVAILABLE))
            } else if (cue.id !in modes && wantsText) {
                modes[cue.id] = RoomSpeechMode.TEXT
            } else if (cue.id !in modes && cue.audioId != null && cue.durationMillis > 0 && preparing.add(cue.id)) {
                add(RoomSpeechEffect.Prepare(cue, key(cue.id)))
            }
            addAll(releaseReadiness(cue))
        }
        // Do not stop one sentence merely because the nominal next sentence has started.
        val active = activeCue
        if (active != null) {
            if (activeMode == RoomSpeechMode.TEXT && activeReadUntil == null)
                activeReadUntil = maxOf(active.readUntil ?: now, now + roomSpeechTextDuration(active.entry.text))
            if (activeMode == RoomSpeechMode.TEXT && activeReadUntil?.let { now >= it } == true) {
                finished += active.id
                activeId = null
                activeReadUntil = null
                add(ack(active.id, RoomSpeechAckStatus.FINISHED, RoomSpeechMode.TEXT))
            } else if (activeMode == RoomSpeechMode.AUDIO && active.deadlineAt?.let { now >= it } == true) {
                addAll(failed(key(active.id)))
            }
        }
        if (activeId == null) {
            val next = value.cues.firstOrNull { it.id !in finished && it.id !in ignored }
            val start = next?.startsAt
            if (next != null && start != null && start <= now && next.id in modes) {
                activeId = next.id
                displayed = next
                activeStartedAt = now
                activeReadUntil = null
                if (modes[next.id] == RoomSpeechMode.AUDIO) {
                    val offset = if (recoveredBatch && start < joinedAt) (now - start).coerceAtLeast(0) else 0L
                    if (offset >= next.durationMillis) {
                        addAll(failed(key(next.id), RoomSpeechFailure.RESUMED_AS_TEXT))
                    } else {
                        activeStartedAt = now - offset
                        add(RoomSpeechEffect.Play(next, offset, key(next.id)))
                    }
                } else {
                    activeReadUntil = maxOf(next.readUntil ?: now, now + roomSpeechTextDuration(next.entry.text))
                }
            }
        }
    }

    /** Shift only local reading clocks. Authority cue/deadline timestamps arrive shifted by server. */
    fun resumeAfterPause(durationMillis: Long) {
        val delta = durationMillis.coerceAtLeast(0)
        if (activeId != null) {
            activeStartedAt += delta
            activeReadUntil = activeReadUntil?.plus(delta)
        }
    }

    /** Ready means a validated source was assigned, never a claim of decoder buffering. */
    fun prepared(event: RoomSpeechEventKey): List<RoomSpeechEffect> {
        val cueId = event.cueId
        if (!valid(event) || cueId !in preparing || cueId in modes) return emptyList()
        preparing -= cueId
        modes[cueId] = RoomSpeechMode.AUDIO
        return releaseReadiness(checkNotNull(presentation).cues.first { it.id == cueId })
    }

    fun completed(event: RoomSpeechEventKey): List<RoomSpeechEffect> {
        val cueId = event.cueId
        if (!valid(event) || activeId != cueId || modes[cueId] != RoomSpeechMode.AUDIO || !finished.add(cueId)) return emptyList()
        activeId = null
        activeReadUntil = null
        return listOf(ack(cueId, RoomSpeechAckStatus.FINISHED, RoomSpeechMode.AUDIO))
    }

    fun failed(event: RoomSpeechEventKey, reason: RoomSpeechFailure = RoomSpeechFailure.PLAYBACK_UNAVAILABLE): List<RoomSpeechEffect> {
        val cueId = event.cueId
        if (!valid(event) || cueId in finished || cueId in ignored || modes[cueId] == RoomSpeechMode.TEXT) return emptyList()
        preparing -= cueId
        modes[cueId] = RoomSpeechMode.TEXT
        val cue = checkNotNull(presentation).cues.first { it.id == cueId }
        deferredFailures[cueId] = reason
        return buildList {
            if (activeId == cueId) { activeReadUntil = null; add(RoomSpeechEffect.Stop) }
            if (!held(cue)) {
                deferredFailures.remove(cueId)
                readySent += cueId
                add(ack(cueId, RoomSpeechAckStatus.FAILED, RoomSpeechMode.AUDIO, reason))
            }
        }
    }

    private fun held(cue: RoomSpeechCue): Boolean = cue.kind == RoomSpeechKind.ENDING && !endingReady && cue.startsAt == null

    private fun releaseReadiness(cue: RoomSpeechCue): List<RoomSpeechEffect> {
        val mode = modes[cue.id] ?: return emptyList()
        if (held(cue) || !readySent.add(cue.id)) return emptyList()
        val failure = deferredFailures.remove(cue.id)
        return listOf(if (failure != null) ack(cue.id, RoomSpeechAckStatus.FAILED, RoomSpeechMode.AUDIO, failure)
            else ack(cue.id, RoomSpeechAckStatus.READY, mode))
    }

    private fun key(cueId: String): RoomSpeechEventKey {
        val value = checkNotNull(presentation)
        return RoomSpeechEventKey(value.sessionId, value.batchId, cueId, generation)
    }

    private fun valid(event: RoomSpeechEventKey) = presentation?.busy == true &&
        event.connectionGeneration == generation && event.sessionId == presentation?.sessionId &&
        event.batchId == presentation?.batchId && presentation?.cues?.any { it.id == event.cueId } == true

    private fun ack(cueId: String, status: RoomSpeechAckStatus, mode: RoomSpeechMode, reason: RoomSpeechFailure? = null): RoomSpeechEffect.Ack {
        val value = checkNotNull(presentation)
        return RoomSpeechEffect.Ack(RoomSpeechAcknowledgement(value.sessionId, value.batchId, cueId, status, mode, reason))
    }

    // Avoid the name clear(): inside buildList it resolves to the effects list's clear().
    private fun resetPlayback() {
        presentation = null
        modes.clear(); preparing.clear(); finished.clear(); ignored.clear()
        readySent.clear(); deferredFailures.clear()
        activeId = null; displayed = null
        activeReadUntil = null
    }
}
