package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable

/** A proposal within the lighthouse's supported mechanics, never a state mutation. */
@Serializable
data class GameMasterDecision(val action: DemoAction?, val narration: String) {
    init {
        require(narration.isNotBlank() && narration.length <= 2_000) { "Invalid narrator response" }
    }
}
