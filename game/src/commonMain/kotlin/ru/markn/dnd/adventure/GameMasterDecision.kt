package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

/** A proposal, never a state mutation. nextPlayerId selects the actor after completion; null uses local order. */
@Serializable
data class GameMasterDecision(val action: String?, val narration: String, val plan: SceneActionPlan? = null,
    val nextPlayerId: String? = null, val speakerNpcId: String? = null,
    val inputPresentation: List<InputUtterance>? = null) {
    constructor(action: ScenarioAction, narration: String, plan: SceneActionPlan? = null, nextPlayerId: String? = null) :
        this(action.id, narration, plan, nextPlayerId)
    init {
        require(narration.isNotBlank() && narration.length <= 2_000) { "Invalid narrator response" }
    }
}

@Serializable
enum class InputUtteranceKind { SPEECH, ACTION }

/** Presentation of an intention only; never evidence that an action succeeded. */
@Serializable
data class InputUtterance(val kind: InputUtteranceKind, val text: String) {
    init { require(text.isNotBlank() && text.length <= 1_000) }
}

fun validateInputPresentation(parts: List<InputUtterance>) {
    require(parts.size in 1..4 && parts.sumOf { it.text.length } <= 2_000)
}

fun AdventureState.inputEntries(parts: List<InputUtterance>): List<JournalEntry> {
    validateInputPresentation(parts)
    val firstId = (journal.lastOrNull()?.id ?: 0) + 2 // raw input occupies the first ID
    return parts.mapIndexed { index, part -> JournalEntry(firstId + index,
        if (part.kind == InputUtteranceKind.SPEECH) EntryKind.PLAYER else EntryKind.NARRATOR,
        part.text, playerId = activePlayer.id, actorName = activePlayer.hero.name) }
}
