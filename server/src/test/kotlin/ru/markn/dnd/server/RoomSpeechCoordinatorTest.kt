package ru.markn.dnd.server

import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class RoomSpeechCoordinatorTest {
    private val initial get() = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID)
    private fun prepare(coordinator: RoomSpeechCoordinator, now: Long) {
        while (true) {
            val work = coordinator.takePreparations(now)
            if (work.isEmpty()) break
            work.forEach { coordinator.prepared(it.batchId, it.cueId, RoomSpeechAudio("a".repeat(64), 20_000), now) }
        }
    }
    private fun ack(coordinator: RoomSpeechCoordinator, player: String, cue: RoomSpeechCue,
        status: RoomSpeechAckStatus, now: Long, mode: RoomSpeechMode = RoomSpeechMode.AUDIO) =
        coordinator.acknowledge(player, RoomSpeechAcknowledgement(coordinator.view!!.sessionId,
            coordinator.view!!.batchId, cue.id, status, mode), now)
    private fun ready(coordinator: RoomSpeechCoordinator, players: Set<String>, now: Long) {
        coordinator.view!!.cues.forEach { cue -> players.forEach { player -> ack(coordinator, player, cue, RoomSpeechAckStatus.READY, now) } }
    }
    private fun readEnd(coordinator: RoomSpeechCoordinator): Long {
        val cues = coordinator.view!!.cues
        return maxOf(cues.last().readUntil!!, cues.first().startsAt!! + cues.sumOf { it.entry.text.length * 35L } + 1_600)
    }

    private fun endingBatch(): Pair<RoomSpeechCoordinator, AdventureState> {
        val coordinator = RoomSpeechCoordinator()
        val state = initial
        coordinator.observe(state, setOf("a", "b"), false, 1_000)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        val ended = state.copy(ending = ArchiveDemo.COURIER_SAFE)
        coordinator.observe(ended, setOf("a", "b"), false, 200_000)
        assertTrue(coordinator.view!!.cues.all { it.kind == RoomSpeechKind.ENDING })
        return coordinator to ended
    }

    @Test fun endingAudioWaitsForBothClientsFinalVisualsWithoutSpendingItsPlaybackDeadline() {
        val (coordinator, _) = endingBatch()
        prepare(coordinator, 200_000)
        val cue = coordinator.view!!.cues.first()
        ack(coordinator, "a", cue, RoomSpeechAckStatus.READY, 200_000)
        coordinator.tick(212_000) // Longer than the ordinary source-readiness/finish grace.
        assertNull(coordinator.view!!.cues.first().startsAt)
        ack(coordinator, "b", cue, RoomSpeechAckStatus.READY, 212_000)
        val scheduled = coordinator.view!!.cues.first()
        assertEquals(212_350L, scheduled.startsAt)
        assertEquals(scheduled.durationMillis, scheduled.readUntil!! - scheduled.startsAt!!)
        assertTrue(scheduled.deadlineAt!! > scheduled.readUntil!!)
    }

    @Test fun failedEndingSynthesisStillWaitsForVisualReadinessInTextMode() {
        val (coordinator, _) = endingBatch()
        val work = coordinator.takePreparations(200_000)
        work.forEach { coordinator.prepared(it.batchId, it.cueId, null, 200_100) }
        val cue = coordinator.view!!.cues.first()
        assertEquals(RoomSpeechSource.TEXT, cue.source)
        ack(coordinator, "a", cue, RoomSpeechAckStatus.READY, 200_200, RoomSpeechMode.TEXT)
        coordinator.tick(215_000)
        assertNull(coordinator.view!!.cues.first().startsAt)
        ack(coordinator, "b", cue, RoomSpeechAckStatus.READY, 215_000, RoomSpeechMode.TEXT)
        assertEquals(215_350L, coordinator.view!!.cues.first().startsAt)
    }

    @Test fun endingVisualWaitHasAClockWakeupAndDoesNotWaitForDisconnectedPlayers() {
        val (timeout, _) = endingBatch()
        timeout.takePreparations(200_000).forEach { timeout.prepared(it.batchId, it.cueId, null, 214_000) }
        assertEquals(230_000L, timeout.nextWakeAt(229_999))
        timeout.tick(230_000)
        assertEquals(230_350L, timeout.view!!.cues.first().startsAt)
        val (disconnected, ended) = endingBatch()
        prepare(disconnected, 200_000)
        ack(disconnected, "a", disconnected.view!!.cues.first(), RoomSpeechAckStatus.READY, 200_000)
        disconnected.observe(ended, setOf("a"), false, 201_000)
        assertEquals(201_350L, disconnected.view!!.cues.first().startsAt)
    }

    /** An existing dialogue batch may gain ending cues while final visual insertions drain. */
    private fun mixedEndingBatch(): Pair<RoomSpeechCoordinator, AdventureState> {
        val coordinator = RoomSpeechCoordinator()
        val state = initial
        coordinator.observe(state, setOf("a", "b"), false, 1_000)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        val talking = state.copy(journal = state.journal + JournalEntry(2, EntryKind.NARRATOR, "Текущая короткая реплика."))
        coordinator.observe(talking, setOf("a", "b"), true, 200_000)
        coordinator.takePreparations(200_000).forEach {
            coordinator.prepared(it.batchId, it.cueId, RoomSpeechAudio("a".repeat(64), 1_000), 200_000)
        }
        ready(coordinator, setOf("a", "b"), 200_000)
        assertEquals(200_350L, coordinator.view!!.cues.single().startsAt)
        val ended = talking.copy(ending = state.scenario.endings.keys.first())
        coordinator.observe(ended, setOf("a", "b"), false, 200_100)
        assertEquals(RoomSpeechKind.DIALOGUE, coordinator.view!!.cues.first().kind)
        assertTrue(coordinator.view!!.cues.drop(1).all { it.kind == RoomSpeechKind.ENDING })
        return coordinator to ended
    }

    @Test fun appendedEndingAudioWaitsAtKindBoundaryButNotBetweenItsFragments() {
        val (coordinator, _) = mixedEndingBatch()
        prepare(coordinator, 200_100)
        val ending = coordinator.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }
        ack(coordinator, "a", ending, RoomSpeechAckStatus.READY, 200_100)
        coordinator.tick(212_000)
        assertNull(coordinator.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }.startsAt)
        ack(coordinator, "b", ending, RoomSpeechAckStatus.READY, 212_000)
        val endings = coordinator.view!!.cues.filter { it.kind == RoomSpeechKind.ENDING }
        assertEquals(212_000L, endings.first().startsAt)
        assertTrue(endings.size > 1)
        for (index in endings.indices) {
            assertNotNull(endings[index].startsAt)
            assertEquals(endings[index].durationMillis, endings[index].readUntil!! - endings[index].startsAt!!)
            if (index > 0) assertEquals(endings[index - 1].readUntil, endings[index].startsAt)
        }
    }

    @Test fun appendedEndingTextFallbackStillWaitsForBothClientsVisualReadiness() {
        val (coordinator, _) = mixedEndingBatch()
        while (true) {
            val work = coordinator.takePreparations(200_100)
            if (work.isEmpty()) break
            work.forEach { coordinator.prepared(it.batchId, it.cueId, null, 200_100) }
        }
        val ending = coordinator.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }
        assertEquals(RoomSpeechSource.TEXT, ending.source)
        ack(coordinator, "a", ending, RoomSpeechAckStatus.READY, 200_100, RoomSpeechMode.TEXT)
        coordinator.tick(215_000)
        assertNull(coordinator.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }.startsAt)
        ack(coordinator, "b", ending, RoomSpeechAckStatus.READY, 215_000, RoomSpeechMode.TEXT)
        val scheduled = coordinator.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }
        assertEquals(215_000L, scheduled.startsAt)
        assertEquals(roomSpeechTextDuration(scheduled.entry.text), scheduled.readUntil!! - scheduled.startsAt!!)
    }

    @Test fun appendedEndingBoundaryUsesCreationTimeoutAndDisconnectRemovesOnlyItsOwnWaiter() {
        val (timeout, _) = mixedEndingBatch()
        prepare(timeout, 214_000)
        assertEquals(230_100L, timeout.nextWakeAt(230_099))
        timeout.tick(230_100)
        assertEquals(230_100L, timeout.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }.startsAt)

        val (disconnected, ended) = mixedEndingBatch()
        prepare(disconnected, 200_100)
        disconnected.observe(ended, setOf("a"), false, 212_000)
        val ending = disconnected.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }
        assertNull(ending.startsAt) // Removing b cannot fabricate READY for a.
        ack(disconnected, "a", ending, RoomSpeechAckStatus.READY, 213_000)
        assertEquals(213_000L, disconnected.view!!.cues.first { it.kind == RoomSpeechKind.ENDING }.startsAt)
    }

    @Test fun audioFragmentsAreScheduledWithoutPerCueReadinessRoundTripsOrPadding() {
        val state = initial
        val online = setOf("a", "b")
        val coordinator = RoomSpeechCoordinator()
        coordinator.observe(state, online, false, 1_000)
        prepare(coordinator, 1_000)
        val first = coordinator.view!!.cues.first()
        ack(coordinator, "a", first, RoomSpeechAckStatus.READY, 1_000)
        assertNull(coordinator.view!!.cues.first().startsAt)
        ack(coordinator, "b", first, RoomSpeechAckStatus.READY, 1_000)
        val cues = coordinator.view!!.cues
        assertEquals(1_350L, cues.first().startsAt)
        assertTrue(cues.size > 1)
        for (index in 1 until cues.size) assertEquals(cues[index - 1].readUntil, cues[index].startsAt)
        cues.forEach { assertEquals(it.durationMillis, it.readUntil!! - it.startsAt!!) }
        assertTrue(coordinator.view!!.busy)
    }

    @Test fun actualFinishIsRequiredUntilBoundedDeadlineAndDuplicateOrStaleAcksAreIgnored() {
        val coordinator = RoomSpeechCoordinator()
        coordinator.observe(initial, setOf("a"), false, 1_000)
        prepare(coordinator, 1_000)
        ready(coordinator, setOf("a"), 1_000)
        val final = coordinator.view!!.cues.last()
        assertFalse(ack(coordinator, "a", final, RoomSpeechAckStatus.FINISHED, 1_000))
        val end = readEnd(coordinator)
        coordinator.tick(end)
        assertTrue(coordinator.view!!.busy)
        for (cue in coordinator.view!!.cues) ack(coordinator, "a", cue, RoomSpeechAckStatus.FINISHED, end)
        assertEquals(RoomPresentationPhase.COMPLETE, coordinator.view!!.phase)
        assertFalse(ack(coordinator, "a", final, RoomSpeechAckStatus.FINISHED, end))
        val completed = coordinator.view
        coordinator.observe(initial, setOf("a", "newcomer"), false, end + 1)
        assertEquals(completed, coordinator.view)
    }

    @Test fun missingAcksDisconnectedPlayersAndFailedPreparationCannotBlockForever() {
        val coordinator = RoomSpeechCoordinator(preparationMillis = 100, readinessMillis = 100)
        val state = initial
        coordinator.observe(state, setOf("a", "b"), false, 1_000)
        val claimed = coordinator.takePreparations(1_000)
        coordinator.tick(1_101) // Only claimed work times out; future unclaimed cues remain pending.
        assertTrue(coordinator.view!!.cues.filter { cue -> cue.id in claimed.map { it.cueId } }.all { it.source == RoomSpeechSource.TEXT })
        coordinator.observe(state, emptySet(), false, 1_102)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        assertEquals(RoomPresentationPhase.COMPLETE, coordinator.view!!.phase)
    }

    @Test fun previewCommitKeepsStableSegmentIdsAndRestartInvalidatesPendingPreparation() {
        val coordinator = RoomSpeechCoordinator()
        val engine = AdventureEngine()
        val state = initial
        val online = state.players.map { it.id }.toSet()
        coordinator.observe(state, online, false, 1_000)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        val now = 200_000L
        val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, "Поможем архиву. Сначала разберёмся с дверью."))
        val preview = state.previewInputPresentation(parts, now)
        coordinator.observe(preview, online, true, now)
        val beforeIds = coordinator.view!!.cues.map { it.id }
        assertFalse(coordinator.view!!.sealed)
        val work = coordinator.takePreparations(now).first()
        val decision = GameMasterDecision(null, "Товарищи готовы помочь.", nextPlayerId = state.activePlayer.id, inputPresentation = parts)
        val committed = engine.submit(preview, state.activePlayer.id, state.turnId, "Поможем архиву.", decision)
        coordinator.observe(committed, online, false, now + 10)
        val ids = coordinator.view!!.cues.map { it.id }
        assertTrue(ids.containsAll(beforeIds))
        assertEquals(ids.distinct(), ids)
        assertTrue(coordinator.view!!.sealed)
        val restarted = engine.newGame(sessionId = 2, scenarioId = ArchiveDemo.ID)
        coordinator.observe(restarted, online, false, now + 20)
        assertFalse(coordinator.prepared(work.batchId, work.cueId, RoomSpeechAudio("a".repeat(64), 1_000), now + 30))
        assertEquals(2L, coordinator.view!!.sessionId)
    }

    @Test fun mutedClientsResolvePendingSynthesisToReadableText() {
        val coordinator = RoomSpeechCoordinator()
        val state = initial
        coordinator.observe(state, setOf("a"), false, 1_000)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, "Совершенно новая фраза для текстового режима."))
        coordinator.observe(state.previewInputPresentation(parts, 200_000), setOf("a"), true, 200_000)
        val cue = coordinator.view!!.cues.first()
        assertNull(cue.audioId)
        ack(coordinator, "a", cue, RoomSpeechAckStatus.READY, 200_000, RoomSpeechMode.TEXT)
        assertEquals(RoomSpeechSource.TEXT, coordinator.view!!.cues.first().source)
        assertTrue(coordinator.view!!.cues.first().readUntil!! > 200_000)
        assertTrue(coordinator.takePreparations(200_000).isEmpty())
    }

    @Test fun fiveFutureCuesUseTwoPreparationSlotsAndTimeoutOnlyFromClaim() {
        val coordinator = RoomSpeechCoordinator(preparationMillis = 100)
        val state = initial
        coordinator.observe(state, setOf("a"), false, 1_000)
        coordinator.tick(coordinator.view!!.hardDeadlineAt)
        val entries = (2L..6L).map { JournalEntry(it, EntryKind.NARRATOR, "Новая короткая реплика номер $it.") }
        coordinator.observe(state.copy(journal = state.journal + entries), setOf("a"), false, 200_000)
        val first = coordinator.takePreparations(200_000)
        assertEquals(2, first.size)
        assertTrue(coordinator.takePreparations(200_001).isEmpty())
        coordinator.tick(200_101)
        val unclaimed = coordinator.view!!.cues.filter { cue -> cue.id !in first.map { it.cueId } }
        assertEquals(3, unclaimed.size)
        assertTrue(unclaimed.all { it.source == RoomSpeechSource.GENERATED && it.audioId == null })
        val second = coordinator.takePreparations(200_101)
        assertEquals(2, second.size)
        coordinator.tick(200_150)
        assertTrue(coordinator.view!!.cues.filter { cue -> cue.id in second.map { it.cueId } }.all { it.source == RoomSpeechSource.GENERATED })
        second.forEach { assertTrue(coordinator.prepared(it.batchId, it.cueId, RoomSpeechAudio("b".repeat(64), 1_000), 200_151)) }
        assertEquals(1, coordinator.takePreparations(200_152).size)
    }

    @Test fun textReadersHoldTheBatchAndDuplicateFailureCannotRewriteFinishedAudioMode() {
        val coordinator = RoomSpeechCoordinator()
        val state = initial
        coordinator.observe(state, setOf("audio", "text"), false, 1_000)
        prepare(coordinator, 1_000)
        coordinator.view!!.cues.forEach { cue ->
            ack(coordinator, "audio", cue, RoomSpeechAckStatus.READY, 1_000)
            ack(coordinator, "text", cue, RoomSpeechAckStatus.READY, 1_000, RoomSpeechMode.TEXT)
        }
        val end = readEnd(coordinator)
        coordinator.tick(end)
        coordinator.view!!.cues.forEach { ack(coordinator, "audio", it, RoomSpeechAckStatus.FINISHED, end) }
        assertTrue(coordinator.view!!.busy)
        val first = coordinator.view!!.cues.first()
        assertFalse(ack(coordinator, "audio", first, RoomSpeechAckStatus.FAILED, end, RoomSpeechMode.TEXT))
        coordinator.view!!.cues.forEach { ack(coordinator, "text", it, RoomSpeechAckStatus.FINISHED, end, RoomSpeechMode.TEXT) }
        assertEquals(RoomPresentationPhase.COMPLETE, coordinator.view!!.phase)
    }
    @Test fun recoveredInstanceSelectsTextAfterAudioWithoutPrematureCompletion() {
        val coordinator = RoomSpeechCoordinator()
        coordinator.observe(initial, setOf("a", "b"), false, 1_000)
        prepare(coordinator, 1_000)
        ready(coordinator, setOf("a", "b"), 1_000)
        val end = readEnd(coordinator)
        coordinator.forgetParticipantAcknowledgements("b")
        coordinator.awaitParticipantRecovery("b", end)
        coordinator.shiftTimeline(40_000)
        val resumedAt = end + 40_000
        for (cue in coordinator.view!!.cues) ack(coordinator, "a", cue, RoomSpeechAckStatus.FINISHED, resumedAt)
        assertTrue(coordinator.view!!.busy)
        coordinator.tick(resumedAt + 2_999)
        assertTrue(coordinator.view!!.busy) // No completion between Welcome and the recovered READY.
        val last = coordinator.view!!.cues.last()
        assertTrue(ack(coordinator, "b", last, RoomSpeechAckStatus.READY, resumedAt + 2_999, RoomSpeechMode.TEXT))
        assertTrue(coordinator.view!!.busy)
        assertTrue(ack(coordinator, "b", last, RoomSpeechAckStatus.FINISHED, resumedAt + 2_999, RoomSpeechMode.TEXT))
        assertEquals(RoomPresentationPhase.COMPLETE, coordinator.view!!.phase)
    }

    @Test fun recoveryHandshakeIsBoundedAndOldBatchCannotSatisfyIt() {
        val coordinator = RoomSpeechCoordinator()
        coordinator.observe(initial, setOf("a", "b"), false, 1_000)
        prepare(coordinator, 1_000)
        ready(coordinator, setOf("a", "b"), 1_000)
        val end = readEnd(coordinator)
        coordinator.forgetParticipantAcknowledgements("b")
        coordinator.awaitParticipantRecovery("b", end)
        for (cue in coordinator.view!!.cues) ack(coordinator, "a", cue, RoomSpeechAckStatus.FINISHED, end)
        val view = coordinator.view!!
        assertFalse(coordinator.acknowledge("b", RoomSpeechAcknowledgement(view.sessionId, "stale-batch",
            view.cues.last().id, RoomSpeechAckStatus.READY, RoomSpeechMode.TEXT), end))
        assertTrue(coordinator.view!!.busy)
        coordinator.tick(end + 3_000)
        assertEquals(RoomPresentationPhase.COMPLETE, coordinator.view!!.phase)
    }

}
