package ru.markn.dnd.server

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import ru.markn.dnd.adventure.*
import ru.markn.dnd.adventure.diagnostics.GameLogger

/** Explicit, bounded live comparison. Never prints keys, requests, or generated dialogue. */
internal fun probeGameMaster() = runBlocking {
    val settings = ClaudeSettings.fromEnvironment() ?: error("Server AI configuration is missing")
    val models = listOf(settings.model, "claude-haiku-5-5").distinct()
    coroutineScope {
        models.map { model -> async {
            val gm = ClaudeGameMaster(ClaudeSettings(settings.apiKey, settings.baseUrl, model), GameLogger.None)
            try {
                for (case in listOf("npc_dialogue", "free_plan")) {
                    val state = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID).let {
                        if (case == "free_plan") it.copy(scene = it.scene.copy(facts = setOf(ArchiveDemo.WON))) else it
                    }
                    val input = if (case == "npc_dialogue") "Агата, что случилось с архивом и что тебе нужно?"
                        else "Изучаю устройство замка двери и пробую открыть его отмычками, опираясь на интеллект, а не ловкость."
                    val started = TimeSource.Monotonic.markNow()
                    var valid = false
                    var failure: String? = null
                    try {
                        val reply = withTimeout(40_000) { gm.respond(state, input) }
                        val result = AdventureEngine().submit(state, state.activePlayer.id, state.turnId, input, reply)
                        valid = if (case == "free_plan") result.pendingCheck?.ability == Ability.INTELLIGENCE
                        else result.lastSpokenEntry?.kind == EntryKind.NPC &&
                            !Regex("d20|модификатор|сложность|бонус|нейросет|сервер|нажмите", RegexOption.IGNORE_CASE).containsMatchIn(reply.narration)
                    } catch (_: TimeoutCancellationException) { failure = "timeout" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { failure = (problem as? AiFailure)?.fields()?.get("reason") ?: "request_failed" }
                    println(buildJsonObject {
                        put("model", model); put("case", case); put("effort", "low")
                        put("elapsed_ms", started.elapsedNow().inWholeMilliseconds); put("valid", valid)
                        failure?.let { put("failure", it) }
                    })
                }
            } finally { gm.close() }
        } }.awaitAll()
    }
}

/** Two bounded cases on the configured model; the alternate region requires an explicit CLI flag. */
internal fun probeGameMasterCases(alternativeRegion: Boolean = false) = runBlocking {
    val settings = ClaudeSettings.fromEnvironment() ?: error("Server AI configuration is missing")
    val baseUrl = if (alternativeRegion) "https://ai.starimg.space" else settings.baseUrl
    for (case in listOf("npc_dialogue", "free_plan")) {
        val state = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID).let {
            if (case == "free_plan") it.copy(scene = it.scene.copy(facts = setOf(ArchiveDemo.WON))) else it
        }
        val started = TimeSource.Monotonic.markNow()
        var usage = emptyMap<String, String>()
        val gm = ClaudeGameMaster(ClaudeSettings(settings.apiKey, baseUrl, settings.model),
            GameLogger { event -> if (event.name == "ai_usage") usage = event.fields })
        var failure: String? = null
        var failureDetails = emptyMap<String, String>()
        var valid = false
        try {
            val input = if (case == "npc_dialogue") "Агата, что случилось с архивом и что тебе нужно?"
                else "Изучаю устройство замка двери и пробую открыть его отмычками, опираясь на интеллект, а не ловкость."
            val reply = withTimeout(25_000) { gm.respond(state, input) }
            val result = AdventureEngine().submit(state, state.activePlayer.id, state.turnId, input, reply)
            valid = if (case == "npc_dialogue") result.lastSpokenEntry?.kind == EntryKind.NPC
                else result.pendingCheck?.ability == Ability.INTELLIGENCE
        } catch (_: TimeoutCancellationException) { failure = "outer_timeout" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            failure = (problem as? AiFailure)?.reason ?: "request_failed"
            failureDetails = (problem as? AiFailure)?.fields().orEmpty()
        }
        finally { gm.close() }
        println(buildJsonObject {
            put("model", settings.model); put("base_url", baseUrl); put("case", case)
            put("elapsed_ms", started.elapsedNow().inWholeMilliseconds); put("valid", valid)
            failure?.let { put("failure", it) }
            for ((key, value) in failureDetails) put(key, value)
            for ((key, value) in usage) put(key, value)
        })
    }
}
