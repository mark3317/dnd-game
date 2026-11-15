package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class StoryPlaybackRoomsTest {
    private fun assertTimelineShift(before: StoryPlayback, after: StoryPlayback, delta: Long) {
        fun shifted(value: Long) = if (value == 0L) 0L else value + delta
        assertEquals(shifted(before.openingAt), after.openingAt)
        assertEquals(shifted(before.openingEndsAt), after.openingEndsAt)
        assertEquals(shifted(before.endingAt), after.endingAt)
        assertEquals(shifted(before.endingEndsAt), after.endingEndsAt)
        assertEquals(before.speeches.map { it.entry }, after.speeches.map { it.entry })
        assertEquals(before.speeches.map { it.startsAt + delta }, after.speeches.map { it.startsAt })
        assertEquals(before.speeches.map { it.endsAt + delta }, after.speeches.map { it.endsAt })
        assertEquals(before.actorId, after.actorId)
        assertEquals(before.previewEntryId, after.previewEntryId)
        assertEquals(before.previewEntryIds, after.previewEntryIds)
    }

    @Test fun cancelledRequestCannotPublishLateInputPresentation() = runBlocking<Unit> {
        var now = 1000L
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String) = error("Use presentation callback")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин пытается открыть дверь."))
                onInputPresentation(parts)
                exited.complete(Unit)
                GameMasterDecision(null, "Запоздалый ответ", inputPresentation = parts)
            }
        }
        val rooms = Rooms(gameMaster = gm, aiScope = this, playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).room.adventure!!
        now = start.playback.busyUntil
        val accepted = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Открываю дверь"))
        entered.await()
        val request = accepted.room.pendingNarration!!
        val cancelled = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.CANCEL_NARRATION,
            turnId = request.turnId, narrationId = request.id))
        assertNull(cancelled.error)
        release.complete(Unit)
        exited.await()
        yield()
        assertEquals(start, a.connection.updates.value.adventure)
        assertNull(a.connection.updates.value.pendingNarration)
        assertTrue(a.connection.updates.value.adventure!!.playback.speeches.isEmpty())
    }

    @Test fun mixedPresentationIsSharedEarlyAndCommittedOnceWithCorrectVoices() = runBlocking<Unit> {
        var now = 1000L
        val release = CompletableDeferred<Unit>()
        val parts = listOf(InputUtterance(InputUtteranceKind.ACTION, "Рин обращается к хранителю."),
            InputUtterance(InputUtteranceKind.SPEECH, "Я могу помочь."))
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String) = error("Use presentation callback")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
                onInputPresentation(parts)
                release.await()
                return GameMasterDecision(null, "Помогите с механизмом.", speakerNpcId = "KEEPER", inputPresentation = parts)
            }
        }
        val rooms = Rooms(gameMaster = gm, aiScope = this, playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).room.adventure!!
        now = start.playback.busyUntil
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Обращаюсь к хранителю: я могу помочь"))
        val early = withTimeout(3000) { a.connection.updates.first { it.adventure!!.playback.previewEntryId != null } }
        assertNotNull(early.pendingNarration)
        val preview = early.adventure!!
        assertEquals(listOf("narrator", "rin"), preview.playback.speeches.map { preview.speechVoice(it.entry) })
        assertEquals(start.journal, preview.journal)
        assertEquals(early, b.connection.updates.value)
        // Even if playback has already ended, committing must not queue these intentions again.
        now = preview.playback.busyUntil + 1000
        release.complete(Unit)
        val done = withTimeout(3000) { a.connection.updates.first { it.pendingNarration == null } }.adventure!!
        assertEquals(listOf(EntryKind.NPC), done.playback.speeches.map { it.entry.kind })
        for (part in parts) assertEquals(1, done.journal.count { it.isSpoken && it.text == part.text })
    }

    @Test fun failedModelDoesNotSuppressTheNextAuthoredPlayerPhrase() = runBlocking<Unit> {
        var now = 1000L
        val gm = GameMaster { _, _ -> error("provider unavailable") }
        val rooms = Rooms(gameMaster = gm, aiScope = this, playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).room.adventure!!
        now = start.playback.busyUntil
        rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Пробую поговорить"))
        val failed = withTimeout(3000) { a.connection.updates.first { it.gameMasterError != null } }.adventure!!
        assertNull(failed.playback.previewEntryId)
        now = failed.playback.busyUntil
        val retry = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT,
            turnId = failed.turnId, text = Lighthouse.CLIMB.text))
        assertNull(retry.error)
        assertEquals(EntryKind.NARRATOR, retry.room.adventure!!.playback.speeches.first().entry.kind)
    }

    @Test fun acceptedPhraseIsPublishedWhileModelWaitsAndSurvivesReconnect() = runBlocking<Unit> {
        var now = 1000L
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val gm = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String) = error("Use presentation callback")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
                calls++
                val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, input))
                onInputPresentation(parts)
                release.await()
                return GameMasterDecision("TALK", "Входите, друзья.", speakerNpcId = "ARCHIVIST", inputPresentation = parts)
            }
        }
        val rooms = Rooms(gameMaster = gm, aiScope = this, playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID))
        val start = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START)).room.adventure!!
        now = start.playback.busyUntil
        val accepted = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Агата, можно войти?"))
        assertNotNull(accepted.room.pendingNarration)
        assertTrue(accepted.room.adventure!!.playback.speeches.isEmpty())
        val preview = withTimeout(3000) { a.connection.updates.first { it.adventure!!.playback.previewEntryId != null } }.adventure!!
        assertEquals(start.journal, preview.journal)
        assertEquals("Агата, можно войти?", preview.playback.speeches.single().entry.text)
        assertEquals(preview.playback, b.connection.updates.value.adventure!!.playback)
        rooms.disconnect(b.connection)
        assertEquals(RoomLifecycle.PAUSED, a.connection.updates.value.lifecycle)
        assertEquals(listOf(b.connection.playerId), a.connection.updates.value.waitingPlayerIds)
        now += 750
        val resumed = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code, b.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, resumed.welcome.room.lifecycle)
        val resumedState = resumed.welcome.room.adventure!!
        assertTimelineShift(preview.playback, resumedState.playback, 750)
        assertEquals(preview.copy(playback = resumedState.playback), resumedState)
        assertEquals(resumedState.playback, a.connection.updates.value.adventure!!.playback)
        assertEquals(1, calls)
        release.complete(Unit)
        val result = withTimeout(3000) { a.connection.updates.first { it.pendingNarration == null } }.adventure!!
        assertEquals(1, calls)
        assertEquals(1, result.journal.count { it.kind == EntryKind.PLAYER && it.isSpoken })
        assertEquals(1, result.playback.speeches.count { it.entry.kind == EntryKind.PLAYER })
        assertEquals(EntryKind.NPC, result.playback.speeches.last().entry.kind)
    }

    @Test fun roomSharesTimelineRejectsEarlyActionsAndResumesWithoutRestarting() = runBlocking<Unit> {
        var now = 1000L
        val rooms = Rooms(playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        val b = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        val start = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.START)).room.adventure!!
        assertEquals(start.playback, b.connection.updates.value.adventure!!.playback)
        val early = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.PASS, turnId = start.turnId))
        assertNotNull(early.error)
        assertEquals(start, early.room.adventure)
        rooms.disconnect(b.connection)
        assertEquals(RoomLifecycle.PAUSED, a.connection.updates.value.lifecycle)
        assertEquals(listOf(b.connection.playerId), a.connection.updates.value.waitingPlayerIds)
        now += 500
        val resumed = rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code, b.welcome.resumeToken))
        assertEquals(RoomLifecycle.ACTIVE, resumed.welcome.room.lifecycle)
        val resumedState = resumed.welcome.room.adventure!!
        assertTimelineShift(start.playback, resumedState.playback, 500)
        assertEquals(start.copy(playback = resumedState.playback), resumedState)
        assertEquals(resumedState.playback, a.connection.updates.value.adventure!!.playback)
        // Reconnect preserves the remaining interval; the old end must not unlock input early.
        now = start.playback.busyUntil
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.PASS, turnId = start.turnId)).error)
        now = resumedState.playback.busyUntil
        val spoken = rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Осмотреться"))
        assertNull(spoken.error)
        val game = spoken.room.adventure!!
        assertTrue(game.playback.speeches.any { it.entry.kind == EntryKind.NARRATOR })
        val owner = if (game.activePlayer.id == a.connection.playerId) a.connection else resumed.connection
        val blocked = rooms.command(owner, ClientMessage.Command(6, NetworkAction.PASS, turnId = game.turnId))
        assertNotNull(blocked.error)
        now = game.playback.busyUntil
        assertNull(rooms.command(owner, ClientMessage.Command(7, NetworkAction.PASS, turnId = game.turnId)).error)
    }

    @Test fun scheduledStateStillAcceptsAsyncNpcResponseAndOutcome() = runBlocking<Unit> {
        var now = 1000L
        val gm = GameMaster { _, _ -> GameMasterDecision("TALK", "Входите, друзья.", speakerNpcId = "ARCHIVIST") }
        val rooms = Rooms(gameMaster = gm, aiScope = this, playbackClock = { now })
        val a = rooms.joinReady(ClientMessage.Join("A"))
        rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID))
        val start = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START)).room.adventure!!
        now = start.playback.busyUntil
        rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT,
            turnId = start.turnId, text = "Агата, можно войти?"))
        val result = withTimeout(3000) { a.connection.updates.first { it.pendingNarration == null && it.adventure != start } }
        assertEquals(EntryKind.NPC, result.adventure!!.playback.speeches.last().entry.kind)
    }
}
