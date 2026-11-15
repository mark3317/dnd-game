package ru.markn.dnd.server

import java.io.File
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.*

/** Export actual authored text, using exactly the same segmentation as the scene. */
fun exportSpeech(path: String) {
    val state = AdventureEngine().newGame(scenarioId = ArchiveDemo.ID)
    val phrases = buildList {
        state.openingShots().forEach { add(PreparedSpeech(it.text, "narrator")) }
        state.scenario.endings.keys.forEach { ending ->
            state.copy(ending = ending).endingShots().forEach { add(PreparedSpeech(it.text, "narrator")) }
        }
        state.scenario.sites.values.forEach { site ->
            subtitleSegments(site.inspection).forEach { add(PreparedSpeech(it, "narrator")) }
            val voice = when (site.npcId) { "ARCHIVIST" -> "archivist"; "COURIER" -> "courier"; else -> "narrator" }
            subtitleSegments(site.conversation).forEach { add(PreparedSpeech(it, voice)) }
        }
        state.scenario.encounter?.let { encounter ->
            subtitleSegments(encounter.introduction).forEach { add(PreparedSpeech(it, "narrator")) }
        }
    }.distinctBy { it.voice to it.text }
    File(path).apply { parentFile?.mkdirs(); writeText(Json.encodeToString(phrases), Charsets.UTF_8) }
    println("speech_exported count=${phrases.size}")
}
