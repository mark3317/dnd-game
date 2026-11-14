package ru.markn.dnd.server

import kotlinx.serialization.json.*
import ru.markn.dnd.adventure.AdventureState
import ru.markn.dnd.network.NetworkAction

data class PlayerParticipation(
    val playerId: String,
    val heroName: String,
    val online: Boolean,
    val sameLocationAsActive: Boolean,
    val completedActions: Long,
    val rolls: Long,
    val lastActionIndex: Long?,
    val actionsSinceLastAction: Long,
    val focusVisits: Long,
    val actionsSinceLastFocus: Long,
)

/** Immutable request snapshot: counters describe committed actions before the new intent. */
data class ParticipationSnapshot(
    val completedActions: Long,
    val currentPlayerId: String,
    val currentFocusActions: Long,
    val recentActors: List<String>,
    val players: List<PlayerParticipation>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("available", true)
        put("total_completed_actions", completedActions)
        put("current_player_id", currentPlayerId)
        put("completed_actions_in_current_focus", currentFocusActions)
        val waiting = players.filter { it.online && it.playerId != currentPlayerId }
        putJsonArray("not_yet_acted_online_player_ids") {
            waiting.filter { it.lastActionIndex == null }.forEach { add(it.playerId) }
        }
        val longestWait = waiting.maxOfOrNull { it.actionsSinceLastAction }
        putJsonArray("longest_waiting_online_player_ids") {
            waiting.filter { it.actionsSinceLastAction == longestWait }.forEach { add(it.playerId) }
        }
        putJsonArray("recent_action_player_ids") { recentActors.forEach { add(it) } }
        putJsonArray("players") { players.forEach { player -> addJsonObject {
            put("player_id", player.playerId); put("hero_name", player.heroName)
            put("online", player.online); put("same_location_as_active", player.sameLocationAsActive)
            put("completed_actions", player.completedActions); put("rolls", player.rolls)
            put("has_acted", player.lastActionIndex != null)
            put("last_action_index", player.lastActionIndex?.let(::JsonPrimitive) ?: JsonNull)
            put("other_actions_since_last_action", player.actionsSinceLastAction)
            put("focus_visits", player.focusVisits)
            put("other_actions_since_last_focus", player.actionsSinceLastFocus)
        } } }
    }
}

/** Room-owned counters, mutated only under Rooms' mutex; no text or elapsed wall time is stored. */
internal class ParticipationMemory {
    private data class Counts(var actions: Long = 0, var rolls: Long = 0, var lastAction: Long? = null,
        var visits: Long = 0, var lastFocus: Long? = null)
    private val counts = mutableMapOf<String, Counts>()
    private val recentActors = ArrayDeque<String>()
    private var sessionId: Long? = null
    private var total = 0L
    private var focusPlayerId = ""
    private var focusActions = 0L

    fun reset(state: AdventureState) {
        counts.clear()
        state.players.forEach { counts[it.id] = Counts() }
        recentActors.clear()
        sessionId = state.sessionId
        total = 0
        focusPlayerId = state.activePlayer.id
        focusActions = 0
        counts.getValue(focusPlayerId).apply { visits = 1; lastFocus = 0 }
    }

    fun record(before: AdventureState, after: AdventureState, command: NetworkAction) {
        require(before.sessionId == sessionId && after.sessionId == sessionId)
        val rolled = command == NetworkAction.ROLL && after.journal.drop(before.journal.size).any { it.roll != null }
        val completed = rolled || (command == NetworkAction.SUBMIT && after.pendingCheck == null &&
            (after.turnId != before.turnId || (before.ending == null && after.ending != null)))
        if (completed) {
            val actor = before.activePlayer.id
            total++
            counts.getValue(actor).apply { actions++; if (rolled) rolls++; lastAction = total; lastFocus = total }
            focusActions++
            recentActors.addLast(actor)
            if (recentActors.size > 12) recentActors.removeFirst()
        }
        val next = after.activePlayer.id
        if (next != focusPlayerId) {
            focusPlayerId = next
            focusActions = 0
            counts.getValue(next).apply { visits++; lastFocus = total }
        }
    }

    fun snapshot(state: AdventureState, onlinePlayerIds: Set<String>): ParticipationSnapshot {
        require(state.sessionId == sessionId)
        return ParticipationSnapshot(total, state.activePlayer.id, focusActions, recentActors.toList(),
            state.players.map { player ->
                val value = counts.getValue(player.id)
                PlayerParticipation(player.id, player.hero.name, player.id in onlinePlayerIds,
                    player.location == state.activePlayer.location, value.actions, value.rolls, value.lastAction,
                    total - (value.lastAction ?: 0), value.visits,
                    if (player.id == state.activePlayer.id) 0 else total - (value.lastFocus ?: 0))
            })
    }
}
