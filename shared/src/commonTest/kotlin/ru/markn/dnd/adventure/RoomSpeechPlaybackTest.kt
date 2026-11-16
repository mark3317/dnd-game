package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.adventure.ui.*
import ru.markn.dnd.network.*

class RoomSpeechPlaybackTest {
    private fun cue(id: String = "one", start: Long? = null, source: RoomSpeechSource = RoomSpeechSource.GENERATED,
        audioId: String? = "a".repeat(64), kind: RoomSpeechKind = RoomSpeechKind.DIALOGUE, text: String = "Вода поднимается.") = RoomSpeechCue(
        id, kind, JournalEntry(1, EntryKind.NARRATOR, text), "narrator",
        source = source, audioId = audioId, durationMillis = 1000,
        startsAt = start, readUntil = start?.plus(1000), deadlineAt = start?.plus(9000),
    )

    private fun batch(cues: List<RoomSpeechCue>, phase: RoomPresentationPhase = RoomPresentationPhase.PREPARING,
        id: String = "batch", session: Long = 1) = RoomPresentation(session, id, phase, cues, hardDeadlineAt = 30_000)

    private fun RoomSpeechPlayback.tick(value: RoomPresentation?, now: Long = 0, generation: Long = 1,
        connected: Boolean = true, enabled: Boolean = true, seek: Boolean = true, player: Boolean = true, endingReady: Boolean = true) =
        update(value, generation, connected, now, enabled, player, seek, endingReady)

    private fun List<RoomSpeechEffect>.acks() = filterIsInstance<RoomSpeechEffect.Ack>().map { it.value }

    @Test fun resumedTextReadingClockShiftsOnceWithoutReplayingOrRepeatingReadiness() {
        val playback = RoomSpeechPlayback()
        val value = batch(listOf(cue(start = 1000, source = RoomSpeechSource.TEXT, audioId = null)), RoomPresentationPhase.PLAYING)
        playback.tick(value, now = 1000, enabled = false)
        val originalEnd = checkNotNull(playback.activeReadUntil)
        playback.resumeAfterPause(10000)
        val resumed = value.copy(cues = value.cues.map { it.copy(startsAt = it.startsAt?.plus(10000),
            readUntil = it.readUntil?.plus(10000), deadlineAt = it.deadlineAt?.plus(10000)) }, hardDeadlineAt = 40000)
        assertEquals(originalEnd + 10000, playback.activeReadUntil)
        assertTrue(playback.tick(resumed, now = 11000, enabled = false).isEmpty())
        assertTrue(playback.tick(resumed, now = originalEnd + 9999, enabled = false).acks().isEmpty())
        assertEquals(RoomSpeechAckStatus.FINISHED,
            playback.tick(resumed, now = originalEnd + 10000, enabled = false).acks().single().status)
        assertTrue(playback.tick(resumed, now = originalEnd + 10001, enabled = false).isEmpty())
    }

    @Test fun waitsForPublishedAudioSourceAndAcknowledgesReadinessOnce() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue(audioId = null)))
        assertTrue(playback.tick(pending).isEmpty())
        val prepared = pending.copy(cues = listOf(cue()))
        val request = playback.tick(prepared).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertTrue(playback.tick(prepared).isEmpty())
        assertEquals(RoomSpeechAckStatus.READY, playback.prepared(request.key).acks().single().status)
        assertTrue(playback.prepared(request.key).isEmpty())
        assertTrue(playback.tick(prepared, now = 500).none { it is RoomSpeechEffect.Play })
        val playing = prepared.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        val start = playback.tick(playing, now = 1000).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals(0L, start.offsetMillis)
        assertTrue(playback.tick(playing, now = 1200).none { it is RoomSpeechEffect.Play })
    }

    @Test fun mutedClientDoesNotWaitForGpuButStillWaitsForTextReading() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue(audioId = null)))
        val ready = playback.tick(pending, enabled = false).acks().single()
        assertEquals(RoomSpeechMode.TEXT, ready.mode)
        assertEquals(RoomSpeechAckStatus.READY, ready.status)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000, audioId = null)))
        assertTrue(playback.tick(playing, now = 1000, enabled = false).isEmpty())
        assertTrue(playback.tick(playing, now = 1999, enabled = false).isEmpty())
        assertEquals(RoomSpeechAckStatus.FINISHED, playback.tick(playing, now = 2000, enabled = false).acks().single().status)
        assertTrue(playback.tick(playing, now = 2100, enabled = false).isEmpty())
    }

    @Test fun fallbackAndMissingPlayerUseTextWithoutTryingAudio() {
        for ((source, player) in listOf(RoomSpeechSource.TEXT to true, RoomSpeechSource.PREPARED to false)) {
            val playback = RoomSpeechPlayback()
            val ready = playback.tick(batch(listOf(cue(source = source))), player = player)
            assertEquals(RoomSpeechMode.TEXT, ready.acks().single().mode)
            assertTrue(ready.none { it is RoomSpeechEffect.Prepare || it is RoomSpeechEffect.Play })
        }
    }

    @Test fun allPartsOfOneJournalEntryPlayInOrderWithAndWithoutAudio() {
        val full = "Агата поднимает голову от описи и тихо отвечает. Точного времени я не знаю, но вода уже подступает к нижним полкам. Нам нужно спешить, чтобы успеть вынести документы."
        val parts = speechSegments(full)
        assertTrue(parts.size > 1)
        assertEquals(full, parts.joinToString(" "))
        for (audio in listOf(true, false)) {
            val playback = RoomSpeechPlayback()
            val cues = parts.mapIndexed { index, text -> cue("entry:1:$index", text = text,
                source = if (audio) RoomSpeechSource.GENERATED else RoomSpeechSource.TEXT) }
            val preparing = batch(cues).copy(hardDeadlineAt = 120_000)
            playback.tick(preparing, enabled = audio).filterIsInstance<RoomSpeechEffect.Prepare>()
                .forEach { playback.prepared(it.key) }
            val playing = preparing.copy(phase = RoomPresentationPhase.PLAYING,
                cues = cues.mapIndexed { index, cue -> cue.copy(startsAt = 1000L + index * 1000,
                    readUntil = 2000L + index * 1000, deadlineAt = 110_000) })
            var now = 1000L
            var effects = playback.tick(playing, now = now, enabled = audio)
            for (cue in cues) {
                assertEquals(cue.entry.text, playback.activeCue?.entry?.text)
                if (audio) {
                    val play = effects.filterIsInstance<RoomSpeechEffect.Play>().single()
                    assertEquals(cue.id, play.cue.id)
                    assertEquals(0L, play.offsetMillis)
                    playback.completed(play.key)
                    now += 1000
                } else now = checkNotNull(playback.activeReadUntil)
                effects = playback.tick(playing, now = now, enabled = audio)
            }
            assertNull(playback.activeCue)
        }
    }

    @Test fun delayedUninterruptedQueueNeverSeeksOrSkipsTheNextSentence() {
        val playback = RoomSpeechPlayback()
        val preparing = batch(listOf(cue(), cue("two")))
        playback.tick(preparing).filterIsInstance<RoomSpeechEffect.Prepare>().forEach { playback.prepared(it.key) }
        val playing = preparing.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000), cue("two", start = 2000)))
        val first = playback.tick(playing, now = 1000).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertTrue(playback.tick(playing, now = 2700).none { it is RoomSpeechEffect.Play })
        assertEquals("one", playback.activeCue?.id)
        playback.completed(first.key)
        val next = playback.tick(playing, now = 2800).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals("two", next.cue.id)
        assertEquals(0L, next.offsetMillis)
    }

    @Test fun reconnectSeeksOnlyCurrentSentenceAndDoesNotReplayPastSentences() {
        val playback = RoomSpeechPlayback()
        val playing = batch(listOf(cue(start = 1000), cue("two", start = 2000)), RoomPresentationPhase.PLAYING)
        val request = playback.tick(playing, now = 2400).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertEquals("two", request.cue.id)
        playback.prepared(request.key)
        val play = playback.tick(playing, now = 2500).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals(500L, play.offsetMillis)
    }

    @Test fun unverifiedSeekBackendRecoversCurrentSentenceAsTextButPlaysFutureNormally() {
        val playback = RoomSpeechPlayback()
        val playing = batch(listOf(cue(start = 1000), cue("two", start = 2000)), RoomPresentationPhase.PLAYING)
        val initial = playback.tick(playing, now = 1400, seek = false)
        assertEquals(RoomSpeechMode.TEXT, initial.acks().single().mode)
        val future = initial.filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertEquals("two", future.cue.id)
        playback.prepared(future.key)
        val next = playback.tick(playing, now = 2000, seek = false).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals("two", next.cue.id)
        assertEquals(0L, next.offsetMillis)
    }

    @Test fun mutingStopsAudioAndDowngradesOnlyOnceWithoutStartingItAgain() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue()))
        playback.prepared(playback.tick(pending).filterIsInstance<RoomSpeechEffect.Prepare>().single().key)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        playback.tick(playing, now = 1000)
        val muted = playback.tick(playing, now = 1300, enabled = false)
        assertTrue(RoomSpeechEffect.Stop in muted)
        assertEquals(RoomSpeechFailure.USER_MUTED, muted.acks().single().reason)
        assertEquals(RoomSpeechMode.TEXT, playback.activeMode)
        assertTrue(playback.tick(playing, now = 1500, enabled = false).isEmpty())
        assertTrue(playback.tick(playing, now = 1600, enabled = true).none { it is RoomSpeechEffect.Play })
    }

    @Test fun staleEndedAndPreparationCannotAcknowledgeNewConnectionOrNewBatch() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue()))
        val oldKey = playback.tick(pending).filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        playback.prepared(oldKey)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        playback.tick(playing, now = 1000)
        val reconnected = playback.tick(playing, now = 1200, generation = 2)
        assertTrue(RoomSpeechEffect.Stop in reconnected)
        assertNull(playback.activeCue)
        val newKey = reconnected.filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        assertTrue(playback.completed(oldKey).isEmpty())
        assertTrue(playback.prepared(oldKey).isEmpty())
        assertTrue(playback.failed(oldKey).isEmpty())
        playback.prepared(newKey)
        playback.tick(playing, now = 1300, generation = 2)
        assertEquals(RoomSpeechAckStatus.FINISHED, playback.completed(newKey).acks().single().status)
        assertTrue(playback.completed(newKey).isEmpty())
        val later = batch(listOf(cue()), id = "different-batch")
        assertEquals("different-batch", playback.tick(later, generation = 2)
            .filterIsInstance<RoomSpeechEffect.Prepare>().single().key.batchId)
        assertTrue(playback.completed(newKey).isEmpty())
    }

    @Test fun previewCommitAndAppendingCuesDoNotDuplicateExistingPlayback() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue())).copy(sealed = false)
        val first = playback.tick(pending).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        playback.prepared(first.key)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        playback.tick(playing, now = 1000)
        assertTrue(playback.tick(playing.copy(sealed = true), now = 1100).isEmpty())
        val append = playing.copy(cues = playing.cues + cue("two"))
        assertEquals("two", playback.tick(append, now = 1200).filterIsInstance<RoomSpeechEffect.Prepare>().single().cue.id)
        assertEquals("one", playback.activeCue?.id)
    }

    @Test fun completedEpilogueSnapshotIsNeverReplayedAndDisconnectInvalidatesCallbacks() {
        val ending = cue(kind = RoomSpeechKind.ENDING, start = 1000)
        val complete = batch(listOf(ending), RoomPresentationPhase.COMPLETE)
        val playback = RoomSpeechPlayback()
        assertTrue(playback.tick(complete, now = 5000).isEmpty())
        assertNull(playback.activeCue)
        val pending = batch(listOf(cue()), id = "new")
        val key = playback.tick(pending).filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        playback.prepared(key)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        assertEquals("one", playback.tick(playing, now = 1000).filterIsInstance<RoomSpeechEffect.Play>().single().cue.id)
        assertNotNull(playback.activeCue)
        assertTrue(RoomSpeechEffect.Stop in playback.tick(playing, now = 1100, connected = false))
        assertNull(playback.activeCue)
        assertNull(playback.displayedCue)
        assertTrue(playback.prepared(key).isEmpty())
        assertTrue(playback.completed(key).isEmpty())
    }

    @Test fun mutedReaderGetsEveryLongSentenceEvenWhenTheAudioScheduleIsShort() {
        val playback = RoomSpeechPlayback()
        val text = "а".repeat(100)
        val first = cue(start = 1000, text = text).copy(durationMillis = 200, readUntil = 1200)
        val second = cue("two", start = 1200, text = text).copy(durationMillis = 200, readUntil = 1400)
        val preparing = batch(listOf(first.copy(startsAt = null), second.copy(startsAt = null)))
        playback.tick(preparing, enabled = false)
        val playing = preparing.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(first, second))
        playback.tick(playing, now = 1000, enabled = false)
        assertEquals(4500L, playback.activeReadUntil)
        assertTrue(playback.tick(playing, now = 2000, enabled = false).isEmpty())
        assertEquals("one", playback.activeCue?.id)
        assertEquals("one", playback.tick(playing, now = 4500, enabled = false).acks().single().cueId)
        assertEquals("two", playback.activeCue?.id)
        assertEquals(4500L, playback.activeStartedAt)
        assertEquals(8000L, playback.activeReadUntil)
        assertTrue(playback.tick(playing, now = 7999, enabled = false).isEmpty())
        assertEquals("two", playback.tick(playing, now = 8000, enabled = false).acks().single().cueId)
    }

    @Test fun queuedAudioWaitsForLongFallbackTextInsteadOfInterruptingIt() {
        val playback = RoomSpeechPlayback()
        val first = cue(source = RoomSpeechSource.TEXT, text = "а".repeat(100))
        val second = cue("two")
        val preparing = batch(listOf(first, second))
        val source = playback.tick(preparing).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        playback.prepared(source.key)
        val playing = preparing.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(
            first.copy(startsAt = 1000, readUntil = 1200), second.copy(startsAt = 1200, readUntil = 2200),
        ))
        playback.tick(playing, now = 1000)
        assertTrue(playback.tick(playing, now = 2000).none { it is RoomSpeechEffect.Play })
        val next = playback.tick(playing, now = 4500).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals("two", next.cue.id)
        assertEquals(0L, next.offsetMillis)
    }

    @Test fun hardDeadlineStopsAudioWithoutUnlockingAuthorityOwnedPresentation() {
        val playback = RoomSpeechPlayback()
        val pending = batch(listOf(cue())).copy(hardDeadlineAt = 1500)
        val key = playback.tick(pending).filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        playback.prepared(key)
        val playing = pending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(start = 1000)))
        playback.tick(playing, now = 1000)
        val expired = playback.tick(playing, now = 1500)
        assertTrue(RoomSpeechEffect.Stop in expired)
        assertEquals(RoomSpeechAckStatus.FAILED, expired.acks().single().status)
        assertNull(playback.activeCue)
        assertTrue(playing.busy)
        assertTrue(playback.tick(playing, now = 1600).isEmpty())
    }

    @Test fun endingSourcePreparesDuringFinalVisualsButReadinessAndAudioWaitForTheirRelease() {
        val playback = RoomSpeechPlayback()
        val ending = batch(listOf(cue(kind = RoomSpeechKind.ENDING)))
        val request = playback.tick(ending, endingReady = false).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertTrue(playback.prepared(request.key).isEmpty())
        assertTrue(playback.tick(ending, now = 7000, endingReady = false).isEmpty())
        assertNull(playback.activeCue)
        assertEquals(RoomSpeechAckStatus.READY, playback.tick(ending, now = 8000).acks().single().status)
        assertTrue(playback.tick(ending, now = 8001).isEmpty())
        val scheduled = ending.copy(phase = RoomPresentationPhase.PLAYING, cues = listOf(cue(kind = RoomSpeechKind.ENDING, start = 8350)))
        val play = playback.tick(scheduled, now = 8400).filterIsInstance<RoomSpeechEffect.Play>().single()
        assertEquals(0L, play.offsetMillis, "Waiting for our own final visuals is not a reconnect seek")
    }

    @Test fun mutedEndingAndPreparationFailureCannotMarkTheServerReadyWhileHeld() {
        val ending = batch(listOf(cue(kind = RoomSpeechKind.ENDING)))
        val muted = RoomSpeechPlayback()
        assertTrue(muted.tick(ending, enabled = false, endingReady = false).isEmpty())
        assertTrue(muted.tick(ending, now = 5000, enabled = false, endingReady = false).isEmpty())
        assertEquals(RoomSpeechMode.TEXT, muted.tick(ending, now = 6000, enabled = false).acks().single().mode)
        val failed = RoomSpeechPlayback()
        val request = failed.tick(ending, endingReady = false).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertTrue(failed.failed(request.key).isEmpty())
        assertTrue(failed.tick(ending, now = 5000, endingReady = false).isEmpty())
        val released = failed.tick(ending, now = 6000).acks().single()
        assertEquals(RoomSpeechAckStatus.FAILED, released.status)
        assertTrue(failed.tick(ending, now = 6001).isEmpty())
    }

    @Test fun mutingAnAssignedHeldEndingDefersFailureAndDoesNotSendReadyFirst() {
        val playback = RoomSpeechPlayback()
        val ending = batch(listOf(cue(kind = RoomSpeechKind.ENDING)))
        val request = playback.tick(ending, endingReady = false).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        assertTrue(playback.prepared(request.key).isEmpty())
        assertTrue(playback.tick(ending, now = 1000, enabled = false, endingReady = false).isEmpty())
        val result = playback.tick(ending, now = 2000, enabled = false).acks().single()
        assertEquals(RoomSpeechAckStatus.FAILED, result.status)
        assertEquals(RoomSpeechFailure.USER_MUTED, result.reason)
    }

    @Test fun publishedAuthorityStartReleasesHeldEndingAtTheBoundedFallback() {
        val playback = RoomSpeechPlayback()
        val ending = batch(listOf(cue(kind = RoomSpeechKind.ENDING)))
        val request = playback.tick(ending, endingReady = false).filterIsInstance<RoomSpeechEffect.Prepare>().single()
        playback.prepared(request.key)
        val published = ending.copy(phase = RoomPresentationPhase.PLAYING,
            cues = listOf(cue(kind = RoomSpeechKind.ENDING, start = 1000)))
        val effects = playback.tick(published, now = 1000, endingReady = false)
        assertEquals(RoomSpeechAckStatus.READY, effects.acks().single().status)
        assertEquals(0L, effects.filterIsInstance<RoomSpeechEffect.Play>().single().offsetMillis)
    }

    @Test fun disconnectDuringHeldEndingInvalidatesDeferredReadinessOfTheOldPlayer() {
        val playback = RoomSpeechPlayback()
        val ending = batch(listOf(cue(kind = RoomSpeechKind.ENDING)))
        val old = playback.tick(ending, endingReady = false).filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        playback.prepared(old)
        assertTrue(RoomSpeechEffect.Stop in playback.tick(ending, connected = false, endingReady = false))
        assertTrue(playback.prepared(old).isEmpty())
        assertTrue(playback.failed(old).isEmpty())
        val replacement = playback.tick(ending, generation = 2, endingReady = false)
            .filterIsInstance<RoomSpeechEffect.Prepare>().single().key
        assertTrue(playback.prepared(replacement).isEmpty())
        assertTrue(playback.completed(old).isEmpty())
        assertEquals(RoomSpeechAckStatus.READY, playback.tick(ending, generation = 2).acks().single().status)
    }
}
