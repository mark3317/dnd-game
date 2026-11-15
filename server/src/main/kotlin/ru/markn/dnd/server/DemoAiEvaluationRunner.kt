package ru.markn.dnd.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.GameLogger
import kotlin.time.TimeSource

internal data class DemoEvaluationOptions(val ids: List<String>, val maxRequests: Int, val report: Path, val review: Path?, val revision: String)

/** No implicit live default: every run names its cases, request ceiling, and report file. */
internal fun demoEvaluationOptions(args: Array<String>, corpus: List<DemoAiCase>): DemoEvaluationOptions {
    val known = setOf("--evaluate-ai", "--case-set", "--cases", "--max-requests", "--report", "--review", "--revision")
    require(args.firstOrNull() == "--evaluate-ai") { "Use --evaluate-ai explicitly" }
    val values = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val key = args[index++]
        require(key in known && key != "--evaluate-ai" && key !in values && index < args.size) { "Invalid evaluation arguments" }
        values[key] = args[index++]
    }
    require(values["--case-set"] == DEMO_AI_CORPUS_VERSION) { "Specify --case-set demo-v1" }
    val selection = requireNotNull(values["--cases"]) { "Specify --cases GM01,GM02 or all" }
    val ids = if (selection == "all") corpus.map { it.id } else selection.split(',')
    require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { id -> corpus.any { it.id == id } }) { "Unknown or duplicate evaluation case" }
    val limit = requireNotNull(values["--max-requests"]?.toIntOrNull()) { "Specify --max-requests" }
    require(limit in 1..150 && ids.size <= limit) { "Case count exceeds explicit request limit (maximum150)" }
    val root = Path.of("reports", "local-ai-evaluation").toAbsolutePath().normalize()
    fun output(key: String): Path? = values[key]?.let {
        val target = Path.of(it).toAbsolutePath().normalize()
        require(target.startsWith(root) && target != root) { "Evaluation files must be inside reports/local-ai-evaluation" }
        require(!Files.exists(target)) { "Evaluation output already exists; choose a new filename" }
        target
    }
    val report = requireNotNull(output("--report")) { "Specify --report" }
    val review = output("--review")
    require(review != report) { "Report and review must use separate files" }
    val revision = requireNotNull(values["--revision"]) { "Specify --revision (commit or reviewed worktree label)" }
    require(revision.matches(Regex("[a-zA-Z0-9._/-]{1,160}"))) { "Invalid revision label" }
    return DemoEvaluationOptions(ids, limit, report, review, revision)
}

internal data class DemoEvaluationResult(
    val id: String, val attempt: Int, val elapsedMillis: Long, val firstPresentationMillis: Long?,
    val assessment: DemoDecisionAssessment?, val failure: String?, val usage: Map<String, String>,
    val responseForReview: String?,
    val failureFields: Map<String, String> = emptyMap(),
    val notRun: Boolean = false,
    val stopReason: String? = null,
)

/** Injected provider makes cap, cancellation and failure accounting testable without a key. */
internal suspend fun runDemoAiEvaluation(
    cases: List<DemoAiCase>, maxRequests: Int, gameMaster: GameMaster, narrator: OutcomeNarrator,
    usage: () -> Map<String, String> = { emptyMap() },
    beforeRequest: (DemoAiCase, Int) -> Unit = { _, _ -> },
    onResult: suspend (DemoEvaluationResult) -> Unit,
): Int {
    require(maxRequests in 1..150 && cases.size <= maxRequests)
    require(cases.map { it.id }.distinct().size == cases.size)
    var attempts = 0
    var consecutiveTransportFailures = 0
    var stopReason: String? = null
    for (case in cases) {
        currentCoroutineContext().ensureActive()
        if (stopReason != null) {
            onResult(DemoEvaluationResult(case.id, attempts, 0, null, null, null, emptyMap(), null,
                notRun = true, stopReason = stopReason))
            continue
        }
        check(attempts < maxRequests)
        attempts++ // Count failed and timed-out attempts too. Never retry.
        beforeRequest(case, attempts)
        val started = TimeSource.Monotonic.markNow()
        var first: Long? = null
        var assessment: DemoDecisionAssessment? = null
        var failure: String? = null
        var review: String? = null
        var failureFields = emptyMap<String, String>()
        try {
            withTimeout(25_000) {
                if (case.outcome != null) {
                    review = narrator.narrate(case.outcome)
                    if (review.isNullOrBlank() || review!!.length > 2_000) failure = "outcome_text_invalid"
                } else {
                    var presentation: List<InputUtterance>? = null
                    val decision = gameMaster.respond(case.state, case.input, case.participation) { parts ->
                        validateInputPresentation(parts)
                        check(presentation == null || presentation == parts) { "Presentation changed" }
                        presentation = parts.toList()
                        if (first == null) first = started.elapsedNow().inWholeMilliseconds
                    }
                    if (presentation == null || decision.inputPresentation != presentation) {
                        failure = "input_presentation_invalid"
                    } else {
                        assessment = assessDemoDecision(case, decision)
                        review = GameWireReviewJson.encodeToString(GameMasterDecision.serializer(), decision)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { failure = "request_timeout" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            val safeFailure = problem as? AiFailure
            failure = safeFailure?.reason?.takeIf { it in evaluationFailureReasons } ?: "evaluation_request_failed"
            failureFields = safeFailure?.fields()?.let(::sanitizedEvaluationFailureFields).orEmpty()
        }
        consecutiveTransportFailures = if (failure in evaluationTransportFailures) consecutiveTransportFailures + 1 else 0
        stopReason = when {
            failureFields["http_status"] in setOf("401", "403") -> "authorization_rejected"
            consecutiveTransportFailures >= 2 -> "consecutive_transport_failures"
            else -> null
        }
        onResult(DemoEvaluationResult(case.id, attempts, started.elapsedNow().inWholeMilliseconds, first,
            assessment, failure, usage().filterKeys { it in setOf("input_tokens", "output_tokens", "total_tokens") }, review, failureFields, stopReason = stopReason))
    }
    return attempts
}

private val evaluationTransportFailures = setOf("transport_timeout", "connection_error", "dns_error", "tls_error", "request_timeout")
private val evaluationFailureReasons = evaluationTransportFailures + setOf(
    "http_error", "provider_response_invalid", "api_client_error", "response_incomplete", "response_too_large",
    "decision_ambiguous_plan", "decision_contract_invalid", "decision_missing_action", "decision_missing_narration",
    "decision_narration_invalid", "decision_next_player_invalid", "decision_not_json", "decision_not_object",
    "decision_unknown_action", "decision_unknown_effect_id", "decision_unknown_item", "input_presentation_missing",
    "input_presentation_invalid", "input_presentation_changed", "outcome_contract_invalid", "outcome_narration_invalid",
)

/** Only bounded status and provider enum values are allowed in evaluation diagnostics. */
internal fun sanitizedEvaluationFailureFields(fields: Map<String, String>): Map<String, String> = buildMap {
    fields["http_status"]?.toIntOrNull()?.takeIf { it in 100..599 }?.let { put("http_status", it.toString()) }
    val allowedTypes = setOf("invalid_request_error", "authentication_error", "permission_error", "not_found_error",
        "rate_limit_error", "api_error", "overloaded_error")
    fields["provider_error_type"]?.takeIf { it in allowedTypes }?.let { put("provider_error_type", it) }
}

private val GameWireReviewJson = Json { encodeDefaults = true }

/** Hash the relevant checked-out sources, including local edits; never inspect env or local config. */
private fun evaluationSourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (root in listOf(Path.of("game/src/commonMain"), Path.of("server/src/main"))) {
        require(Files.isDirectory(root)) { "Run evaluation from the repository root" }
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { file ->
            digest.update(file.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(Files.readAllBytes(file))
        } }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Environment is loaded only in this process. No secrets enter Gradle, CLI args or reports. */
internal fun evaluateDemoAi(args: Array<String>) = runBlocking {
    val corpus = demoAiCases() // Validate fixtures/arguments before constructing any API client.
    val options = demoEvaluationOptions(args, corpus)
    val sourceFingerprint = evaluationSourceFingerprint()
    val settings = requireNotNull(ClaudeSettings.fromEnvironment()) { "AI environment is missing; no requests sent" }
    require(settings.model == "gpt-6-luna" && settings.baseUrl.trimEnd('/') == "https://ai.starimg.ru") {
        "Evaluation requires the project's configured current model and primary origin; no requests sent"
    }
    var capturedUsage = emptyMap<String, String>()
    val gm = ClaudeGameMaster(settings, GameLogger { event -> if (event.name == "ai_usage") capturedUsage = event.fields.toMap() })
    fun create(path: Path) {
        Files.createDirectories(path.parent)
        // Do not follow an existing output, even if it appeared after option validation.
        Files.createFile(path)
    }
    fun append(path: Path, value: JsonObject) = Files.writeString(path, "$value\n", StandardOpenOption.APPEND)
    try {
        create(options.report)
        options.review?.let(::create)
        append(options.report, buildJsonObject {
            put("event", "evaluation_started"); put("corpus", DEMO_AI_CORPUS_VERSION)
            put("model", settings.model); put("max_requests", options.maxRequests)
            put("source_sha256", sourceFingerprint); put("source_revision", options.revision)
            put("selected_cases", options.ids.size)
            put("prose_assessment", "requires_human_review")
        })
        var stopped: String? = null
        var notRunCount = 0
        val attempts = runDemoAiEvaluation(options.ids.map { id -> corpus.single { it.id == id } }, options.maxRequests,
            gm, gm, usage = { capturedUsage }, beforeRequest = { case, attempt ->
                capturedUsage = emptyMap()
                append(options.report, buildJsonObject {
                    put("event", "attempt_started"); put("case", case.id); put("attempt", attempt)
                })
            }) { result ->
            val case = corpus.single { it.id == result.id }
            result.stopReason?.let { stopped = it }
            if (result.notRun) notRunCount++
            append(options.report, buildJsonObject {
                put("case", result.id)
                if (result.notRun) { put("status", "not_run"); put("attempts_so_far", result.attempt) }
                else { put("status", "attempted"); put("attempt", result.attempt); put("elapsed_ms", result.elapsedMillis) }
                result.stopReason?.let { put("stop_reason", it) }
                for ((key, value) in result.failureFields) put(key, value)
                result.firstPresentationMillis?.let { put("first_input_presentation_ms", it) }
                result.failure?.let { put("failure", it) }
                result.assessment?.let {
                    put("classification", it.classification); put("classification_matches", it.classificationMatches)
                    put("engine_accepted", it.engineAccepted); put("invariants_kept", it.invariantsKept)
                    it.planIssue?.let { issue -> put("plan_issue", issue) }
                }
                put("request_kind", if (case.outcome == null) "decision_sse" else "outcome")
                put("prose_assessment", "unreviewed")
                for ((key, value) in result.usage) put(key, value)
            })
            options.review?.let { reviewPath -> result.responseForReview?.let { response ->
                append(reviewPath, buildJsonObject {
                    put("case", result.id); put("rubric", case.rubric)
                    put("synthetic_response", response.replace(settings.apiKey, "[redacted]"))
                    put("prose_assessment", "unreviewed")
                })
            } }
            val status = when { result.notRun -> "not_run"; result.failure == null -> "received"; else -> "failed" }
            println("AI evaluation ${result.id}: attempts=${result.attempt}, status=$status")
        }
        append(options.report, buildJsonObject {
            put("event", "evaluation_finished"); put("attempts", attempts); put("not_run", notRunCount)
            stopped?.let { put("stop_reason", it) }
        })
    } finally { gm.close() }
}
