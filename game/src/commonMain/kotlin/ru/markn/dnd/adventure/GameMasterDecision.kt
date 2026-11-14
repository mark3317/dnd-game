package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

/** A proposal, never a state mutation. nextPlayerId selects the actor after completion; null uses local order. */
@Serializable
data class GameMasterDecision(val action: String?, val narration: String, val plan: SceneActionPlan? = null,
    val nextPlayerId: String? = null) {
    constructor(action: ScenarioAction, narration: String, plan: SceneActionPlan? = null, nextPlayerId: String? = null) :
        this(action.id, narration, plan, nextPlayerId)
    init {
        require(narration.isNotBlank() && narration.length <= 2_000) { "Invalid narrator response" }
    }
}
