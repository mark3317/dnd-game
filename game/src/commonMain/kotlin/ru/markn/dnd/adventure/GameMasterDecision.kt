package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

/** A proposal within the selected scenario's supported mechanics, never a state mutation. */
@Serializable
data class GameMasterDecision(val action: String?, val narration: String, val plan: SceneActionPlan? = null) {
    constructor(action: ScenarioAction, narration: String, plan: SceneActionPlan? = null) : this(action.id, narration, plan)
    init {
        require(narration.isNotBlank() && narration.length <= 2_000) { "Invalid narrator response" }
    }
}
