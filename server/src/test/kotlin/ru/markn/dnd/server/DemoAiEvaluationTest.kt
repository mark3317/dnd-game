package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlin.test.*
import ru.markn.dnd.adventure.*

class DemoAiEvaluationTest {
    private val corpus by lazy { demoAiCases() }
    private fun arguments(vararg extra: String) = arrayOf("--evaluate-ai", "--case-set", "demo-v1",
        "--cases", "GM03", "--max-requests", "1", "--report", "reports/local-ai-evaluation/test-unused.jsonl", "--revision", "offline-test", *extra)

    @Test fun corpusBuildsThirtyReachableSnapshotsAndConfirmedOutcomes() {
        assertEquals((1..30).map { "GM%02d".format(it) }, corpus.map { it.id })
        assertTrue(corpus.all { it.state.scenarioId == ArchiveDemo.ID })
        assertTrue(corpus.filter { it.id in setOf("GM10", "GM11", "GM27") }.all { ArchiveDemo.WON in it.state.scene.facts })
        assertNotNull(corpus.single { it.id == "GM08" }.state.pendingCheck)
        assertFalse(corpus.single { it.id == "GM28" }.outcome!!.activity.roll!!.succeeded)
        assertEquals(FloodedArchive.ORIGINALS_ENDING, corpus.single { it.id == "GM29" }.outcome!!.after.ending)
        assertEquals(FloodedArchive.FLOODED_ENDING, corpus.single { it.id == "GM30" }.outcome!!.after.ending)
        for (case in corpus.filter { it.id.substring(2).toInt() in 19..26 }) {
            assertEquals(case.state.players[0].id, case.state.activePlayer.id, case.id)
            assertEquals(FloodedArchive.HALL, case.state.location, case.id)
        }
        val nearby = corpus.single { it.id == "GM13" }.state
        assertEquals(nearby.players[0].location, nearby.players[1].location)
        val apart = corpus.single { it.id == "GM14" }.state
        assertNotEquals(apart.players[0].location, apart.players[1].location)
    }

    @Test fun liveOptionsRequireExplicitSelectionLimitAndLocalUniquePaths() {
        val options = demoEvaluationOptions(arguments(), corpus)
        assertEquals(listOf("GM03"), options.ids)
        assertEquals(1, options.maxRequests)
        assertNull(options.review)
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(emptyArray(), corpus) }
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(arguments().map { if (it == "GM03") "all" else it }.toTypedArray(), corpus) }
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(arguments().map { if (it == "GM03") "unknown" else it }.toTypedArray(), corpus) }
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(arguments().map { if (it == "1") "151" else it }.toTypedArray(), corpus) }
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(arguments().map { if (it.endsWith("test-unused.jsonl")) "/tmp/report.jsonl" else it }.toTypedArray(), corpus) }
        assertFailsWith<IllegalArgumentException> { demoEvaluationOptions(arguments("--report", "reports/local-ai-evaluation/duplicate.jsonl"), corpus) }
    }

    @Test fun runnerUsesPresentationCallbackCountsFailuresAndNeverRetries() = runBlocking {
        var calls = 0
        var nonStreamingCalls = 0
        var callbacks = 0
        val fake = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String): GameMasterDecision {
                nonStreamingCalls++; error("Nonstreaming path must not be used")
            }
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
                calls++
                if (calls == 2) error("private provider body must not be returned")
                val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, input))
                onInputPresentation(parts); callbacks++
                return GameMasterDecision(null, "Товарищи улыбаются.", nextPlayerId = state.activePlayer.id, inputPresentation = parts)
            }
        }
        val first = corpus.single { it.id == "GM03" }
        val second = first.copy(id = "synthetic-second")
        val results = mutableListOf<DemoEvaluationResult>()
        val allocated = mutableListOf<Int>()
        val count = runDemoAiEvaluation(listOf(first, second), 2, fake, OutcomeNarrator { error("Not an outcome") },
            beforeRequest = { _, attempt -> allocated += attempt }, onResult = { results += it })
        assertEquals(2, count)
        assertEquals(2, calls)
        assertEquals(0, nonStreamingCalls)
        assertEquals(1, callbacks)
        assertEquals(listOf(1, 2), allocated)
        assertTrue(results[0].assessment!!.classificationMatches)
        assertTrue(results[0].assessment!!.invariantsKept)
        assertNotNull(results[0].firstPresentationMillis)
        assertEquals("evaluation_request_failed", results[1].failure)
        assertNull(results[1].responseForReview)
        assertFailsWith<IllegalArgumentException> {
            runDemoAiEvaluation(listOf(first, second), 1, fake, OutcomeNarrator { "" }, onResult = {})
        }
        assertEquals(2, calls)
    }

    @Test fun callerCancellationStopsBeforeAnotherRequestButKeepsAttemptAccounting() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val fake = object : GameMaster {
            override suspend fun respond(state: AdventureState, input: String) = error("Wrong path")
            override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
                onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val request = async {
            runDemoAiEvaluation(listOf(corpus.first(), corpus[1]), 2, fake, OutcomeNarrator { "" },
                beforeRequest = { _, _ -> attempts++ }, onResult = {})
        }
        withTimeout(1_000) { entered.await() }
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(1, attempts)
    }

    @Test fun mechanicsDoNotTreatValidButUnaskedActionsAsSafeRefusal() {
        val case = corpus.single { it.id == "GM21" }
        val decision = GameMasterDecision("GO_YARD", "Выходите во двор.", nextPlayerId = case.state.activePlayer.id)
        val assessment = assessDemoDecision(case, decision)
        assertTrue(assessment.engineAccepted)
        assertFalse(assessment.classificationMatches)
    }

    @Test fun matchingAbilityDoesNotSubstituteAnUnrelatedMechanicalGoal() {
        val case = corpus.single { it.id == "GM11" }
        val unrelated = GameMasterDecision("STUDY_LOCKS", "Изучает чертёж.", nextPlayerId = case.state.activePlayer.id)
        val assessment = assessDemoDecision(case, unrelated)
        assertTrue(assessment.engineAccepted)
        assertFalse(assessment.classificationMatches)
        val plan = case.state.scenario.actions.single { it.id == "OPEN_DOOR" }.planFor(case.state)!!
        val intended = GameMasterDecision(null, "Приступает к замку.", plan = plan.copy(
            check = plan.check!!.copy(ability = Ability.INTELLIGENCE)), nextPlayerId = case.state.activePlayer.id)
        assertTrue(assessDemoDecision(case, intended).classificationMatches)
        assertFalse(assessDemoDecision(case, intended.copy(nextPlayerId = null)).classificationMatches)
        val wrongFact = intended.copy(plan = intended.plan!!.copy(onSuccess = listOf(SceneEffect.Fact("LOCK_PATTERN"))))
        assertFalse(assessDemoDecision(case, wrongFact).classificationMatches)
    }

    private fun fakeEvaluationMaster(failure: () -> AiFailure?): GameMaster = object : GameMaster {
        override suspend fun respond(state: AdventureState, input: String): GameMasterDecision = error("Wrong nonstreaming path")
        override suspend fun respond(state: AdventureState, input: String, participation: ParticipationSnapshot,
            onInputPresentation: suspend (List<InputUtterance>) -> Unit): GameMasterDecision {
            failure()?.let { throw it }
            val parts = listOf(InputUtterance(InputUtteranceKind.SPEECH, input))
            onInputPresentation(parts)
            return GameMasterDecision(null, "Товарищи слушают.", nextPlayerId = state.activePlayer.id, inputPresentation = parts)
        }
    }

    @Test fun twoConsecutiveConnectionFailuresStopWithoutStartingRemainingRequests() = runBlocking {
        var calls = 0
        val failures = listOf("transport_timeout", "dns_error")
        val master = fakeEvaluationMaster { AiFailure(failures[calls++]) }
        val sample = corpus.single { it.id == "GM03" }
        val cases = (1..4).map { sample.copy(id = "synthetic-$it") }
        val started = mutableListOf<Int>()
        val results = mutableListOf<DemoEvaluationResult>()
        val count = runDemoAiEvaluation(cases, 4, master, OutcomeNarrator { error("Not called") },
            beforeRequest = { _, attempt -> started += attempt }, onResult = { results += it })
        assertEquals(2, count)
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(false, false, true, true), results.map { it.notRun })
        assertEquals(listOf(1, 2, 2, 2), results.map { it.attempt })
        assertNull(results.first().stopReason)
        assertTrue(results.drop(1).all { it.stopReason == "consecutive_transport_failures" })
        assertTrue(results.drop(2).all { it.assessment == null && it.responseForReview == null && it.usage.isEmpty() })
    }

    @Test fun successfulResponseResetsTheConsecutiveTransportFailureCounter() = runBlocking {
        var calls = 0
        val sequence = listOf("connection_error", null, "tls_error", "transport_timeout")
        val master = fakeEvaluationMaster { sequence[calls++]?.let { AiFailure(it) } }
        val sample = corpus.single { it.id == "GM03" }
        val results = mutableListOf<DemoEvaluationResult>()
        val count = runDemoAiEvaluation((1..5).map { sample.copy(id = "synthetic-$it") }, 5, master,
            OutcomeNarrator { error("Not called") }, onResult = { results += it })
        assertEquals(4, count)
        assertEquals(4, calls)
        assertNull(results[1].failure)
        assertTrue(results[1].assessment!!.classificationMatches)
        assertEquals("consecutive_transport_failures", results[3].stopReason)
        assertTrue(results.last().notRun)
    }

    @Test fun unauthorizedAndForbiddenStopImmediatelyAndExposeOnlySanitizedEnums() = runBlocking {
        for (status in listOf(401, 403)) {
            var calls = 0
            val master = fakeEvaluationMaster {
                calls++
                AiFailure("http_error", status, if (status == 401) "authentication_error" else "permission_error")
            }
            val sample = corpus.single { it.id == "GM03" }
            val results = mutableListOf<DemoEvaluationResult>()
            val count = runDemoAiEvaluation(listOf(sample, sample.copy(id = "not-run")), 2, master,
                OutcomeNarrator { error("Not called") }, onResult = { results += it })
            assertEquals(1, count)
            assertEquals(1, calls)
            assertEquals("authorization_rejected", results.first().stopReason)
            assertEquals(status.toString(), results.first().failureFields["http_status"])
            assertTrue(results.last().notRun)
            assertEquals(setOf("http_status", "provider_error_type"), results.first().failureFields.keys)
        }
        assertEquals(emptyMap(), sanitizedEvaluationFailureFields(mapOf(
            "http_status" to "999", "provider_error_type" to "private body", "message" to "private key")))
        assertEquals(mapOf("http_status" to "400"), sanitizedEvaluationFailureFields(mapOf(
            "http_status" to "400", "provider_error_type" to "private body", "error_body" to "private key")))
    }

    @Test fun outcomeAuthorizationFailureAlsoStopsBeforeTheNextDecision() = runBlocking {
        var outcomes = 0
        var decisions = 0
        val results = mutableListOf<DemoEvaluationResult>()
        val count = runDemoAiEvaluation(listOf(corpus.single { it.id == "GM28" }, corpus.first()), 2,
            fakeEvaluationMaster { decisions++; null }, OutcomeNarrator {
                outcomes++; throw AiFailure("http_error", 403, "permission_error")
            }, onResult = { results += it })
        assertEquals(1, count)
        assertEquals(1, outcomes)
        assertEquals(0, decisions)
        assertTrue(results.last().notRun)
    }
}
