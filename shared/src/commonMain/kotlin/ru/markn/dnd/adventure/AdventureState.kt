package ru.markn.dnd.adventure

enum class Ability(val title: String, val abbreviation: String) {
    STRENGTH("Сила", "СИЛ"),
    DEXTERITY("Ловкость", "ЛОВ"),
    CONSTITUTION("Телосложение", "ТЕЛ"),
    INTELLIGENCE("Интеллект", "ИНТ"),
    WISDOM("Мудрость", "МДР"),
    CHARISMA("Харизма", "ХАР"),
}

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

enum class Location(val title: String) {
    SHORE("У подножия маяка"),
    LANTERN_ROOM("Фонарная комната"),
}

data class Player(
    val id: String,
    val name: String,
    val hero: Hero,
    val location: Location = Location.SHORE,
) {
    init {
        require(id.isNotBlank()) { "Player ID must not be blank" }
        require(name.isNotBlank()) { "Player name must not be blank" }
    }
}

/** Fixed local order. A completed action or explicit pass advances one turn. */
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
data class TurnId(val sessionId: Long, val number: Long)

enum class Ending(val title: String, val description: String) {
    LIGHTHOUSE_LIT("Свет над рекой", "Маяк снова горит. Паром обходит отмель и добирается до пристани."),
    SIGNAL_GIVEN("Огонь в тумане", "Сигнал замечен: паром останавливается перед отмелью. Люди спасены, хотя маяк ещё предстоит починить."),
    FERRY_LOST("Слишком поздно", "Из тумана доносится удар о мель. Спасательный отряд уже собирается на берегу. Предупредить паром вовремя не удалось."),
}

enum class DemoAction(val text: String) {
    LOOK("Осмотреться"),
    TALK("Поговорить с хранителем"),
    CLIMB("Подняться к фонарю"),
    REPAIR("Починить механизм"),
    SIGNAL("Подать сигнал"),
}

data class CheckId(val sessionId: Long, val actionId: Long)

data class PendingCheck(
    val id: CheckId,
    val playerId: String,
    val turnId: TurnId,
    val action: DemoAction,
    val ability: Ability,
    val difficulty: Int,
    val description: String,
)

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

enum class EntryKind { NARRATOR, PLAYER, CHECK, TURN }

data class JournalEntry(
    val id: Long,
    val kind: EntryKind,
    val text: String,
    val roll: RollResult? = null,
    val playerId: String? = null,
    val actorName: String? = null,
)

data class AdventureState(
    val sessionId: Long,
    val players: List<Player>,
    val turnOrder: TurnOrder,
    val checksRemaining: Int = 3,
    val journal: List<JournalEntry>,
    val pendingCheck: PendingCheck? = null,
    val ending: Ending? = null,
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
    val location: Location get() = activePlayer.location
    val canAct: Boolean get() = pendingCheck == null && ending == null
    val suggestions: List<DemoAction>
        get() = if (location == Location.SHORE) {
            listOf(DemoAction.LOOK, DemoAction.TALK, DemoAction.CLIMB, DemoAction.SIGNAL)
        } else {
            listOf(DemoAction.LOOK, DemoAction.TALK, DemoAction.REPAIR, DemoAction.SIGNAL)
        }
}