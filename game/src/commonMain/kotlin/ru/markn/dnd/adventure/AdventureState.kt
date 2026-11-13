package ru.markn.dnd.adventure

@kotlinx.serialization.Serializable
enum class Ability(val title: String, val abbreviation: String) {
    STRENGTH("Сила", "СИЛ"),
    DEXTERITY("Ловкость", "ЛОВ"),
    CONSTITUTION("Телосложение", "ТЕЛ"),
    INTELLIGENCE("Интеллект", "ИНТ"),
    WISDOM("Мудрость", "МДР"),
    CHARISMA("Харизма", "ХАР"),
}

@kotlinx.serialization.Serializable
data class Hero(
    val name: String,
    val archetype: String,
    val health: Int,
    val maxHealth: Int,
    val modifiers: Map<Ability, Int>,
    val inventory: List<String>,
) {
    fun modifier(ability: Ability): Int = modifiers[ability] ?: 0
}

@kotlinx.serialization.Serializable
data class Player(
    val id: String,
    val name: String,
    val hero: Hero,
    val location: String,
) {
    init {
        require(id.isNotBlank()) { "Player ID must not be blank" }
        require(name.isNotBlank()) { "Player name must not be blank" }
    }
}

/** Fixed local order. A completed action or explicit pass advances one turn. */
@kotlinx.serialization.Serializable
data class TurnOrder(val playerIds: List<String>, val turnNumber: Long = 1) {
    init {
        require(playerIds.isNotEmpty()) { "Turn order must not be empty" }
        require(playerIds.distinct().size == playerIds.size) { "Turn order must contain unique player IDs" }
        require(turnNumber > 0) { "Turn number must be positive" }
    }

    val activeIndex: Int get() = ((turnNumber - 1) % playerIds.size).toInt()
    val activePlayerId: String get() = playerIds[activeIndex]
    val roundNumber: Long get() = (turnNumber - 1) / playerIds.size + 1
    fun next(): TurnOrder = copy(turnNumber = turnNumber + 1)
}

/** Rejects commands from a previous turn, including when the same player acts again. */
@kotlinx.serialization.Serializable
data class TurnId(val sessionId: Long, val number: Long)

@kotlinx.serialization.Serializable
data class CheckId(val sessionId: Long, val actionId: Long)

@kotlinx.serialization.Serializable
data class PendingCheck(
    val id: CheckId,
    val playerId: String,
    val turnId: TurnId,
    val action: String?,
    val ability: Ability,
    val difficulty: Int,
    val description: String,
    val plan: SceneActionPlan? = null,
)

@kotlinx.serialization.Serializable
data class RollResult(
    val checkId: CheckId,
    val playerId: String,
    val ability: Ability,
    val die: Int,
    val modifier: Int,
    val difficulty: Int,
) {
    val total: Int get() = die + modifier
    val succeeded: Boolean get() = total >= difficulty
}

@kotlinx.serialization.Serializable
enum class EntryKind { NARRATOR, PLAYER, CHECK, TURN }

@kotlinx.serialization.Serializable
data class JournalEntry(
    val id: Long,
    val kind: EntryKind,
    val text: String,
    val roll: RollResult? = null,
    val playerId: String? = null,
    val actorName: String? = null,
)

@kotlinx.serialization.Serializable
data class AdventureState(
    val sessionId: Long,
    val players: List<Player>,
    val turnOrder: TurnOrder,
    val checksRemaining: Int = 3,
    val journal: List<JournalEntry>,
    val pendingCheck: PendingCheck? = null,
    val ending: String? = null,
    val scene: SceneState = SceneState(),
    val scenarioId: String = Lighthouse.ID,
) {
    init {
        require(players.isNotEmpty()) { "An adventure needs at least one player" }
        require(players.map { it.id }.distinct().size == players.size) { "Player IDs must be unique" }
        require(turnOrder.playerIds.toSet() == players.map { it.id }.toSet()) {
            "Turn order must contain every player exactly once"
        }
    }

    val activePlayer: Player get() = players.first { it.id == turnOrder.activePlayerId }
    val turnId: TurnId get() = TurnId(sessionId, turnOrder.turnNumber)
    val scenario: ScenarioDefinition get() = requireNotNull(Scenarios.find(scenarioId)) { "Unknown scenario" }
    val location: String get() = activePlayer.location
    val outcome: Outcome? get() = ending?.let { scenario.endings.getValue(it) }
    val canAct: Boolean get() = pendingCheck == null && ending == null
    val suggestions: List<ScenarioAction>
        get() = scenario.actions.filter { action ->
            (action.locations.isEmpty() || location in action.locations) &&
                (action.plan == null || SceneRules.validate(this, requireNotNull(action.planFor(this))) == null)
        }
}
