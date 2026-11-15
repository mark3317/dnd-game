package ru.markn.dnd.adventure

enum class StoryArt { EXTERIOR, HALL, YARD, DOCUMENTS, FLOOD, DAWN, LIGHTHOUSE, LOCATION }
data class StoryShot(val text: String, val art: StoryArt)

/** Presentation only: never applies effects or invents an outcome. */
val AdventureState.isAtOpening: Boolean get() = ending == null && combat == null && pendingCheck == null &&
    turnOrder.turnNumber == 1L && journal.none { it.kind != EntryKind.NARRATOR }

fun AdventureState.openingShots(): List<StoryShot> {
    val archive = scenarioId == ArchiveDemo.ID || scenarioId == FloodedArchive.ID
    val paragraphs = scenario.introduction.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    return paragraphs.mapIndexed { index, text -> StoryShot(text, when {
        !archive -> StoryArt.LIGHTHOUSE
        index == 0 -> StoryArt.EXTERIOR
        index == paragraphs.lastIndex -> StoryArt.HALL
        else -> StoryArt.YARD
    }) }.flatMap { shot -> subtitleSegments(shot.text).map { shot.copy(text = it) } }
}

fun AdventureState.endingShots(): List<StoryShot> {
    val result = outcome ?: return emptyList()
    val archive = scenarioId == ArchiveDemo.ID || scenarioId == FloodedArchive.ID
    val art = when {
        !archive -> StoryArt.LOCATION
        ending == FloodedArchive.ORIGINALS_ENDING -> StoryArt.DOCUMENTS
        ending == FloodedArchive.FLOODED_ENDING -> StoryArt.FLOOD
        ending == ArchiveDemo.DEFEAT -> StoryArt.EXTERIOR
        else -> StoryArt.YARD
    }
    // The authored ending is always available, even while an optional AI epilogue is pending.
    return result.description.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        .chunked(2).mapIndexed { index, lines -> StoryShot(lines.joinToString(" "),
            if (index > 0 && archive && result.success) StoryArt.DAWN else art) }
        .flatMap { shot -> subtitleSegments(shot.text).map { shot.copy(text = it) } }
}

fun AdventureState.storyConsequences(): List<String> = scene.facts.mapNotNull { scenario.facts[it]?.description }
