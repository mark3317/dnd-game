package ru.markn.dnd.combat

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import ru.markn.dnd.adventure.DiceRoller
import ru.markn.dnd.adventure.DiceEvent

// Shared demo combat rules; the authority owns dice and command validation.
@kotlinx.serialization.Serializable
data class Tile(val x: Int, val y: Int) {
    fun distance(other: Tile) = abs(x - other.x) + abs(y - other.y)
    val label: String get() = "${('A'.code + x).toChar()}${y + 1}"
}

@kotlinx.serialization.Serializable
enum class DemoClass { WARRIOR, MAGE, ROGUE, BARD, ENEMY }

@kotlinx.serialization.Serializable
data class DamageDice(val count: Int, val sides: Int, val bonus: Int = 0) {
    val formula get() = "${count}d$sides${if (bonus == 0) "" else "${if (bonus > 0) "+" else ""}$bonus"}"
    val minimum get() = maxOf(0, count + bonus)
    val maximum get() = maxOf(0, count * sides + bonus)
}

@kotlinx.serialization.Serializable
data class DemoFighter(
    val id: String, val name: String, val role: String, val mark: String,
    val tile: Tile, val hp: Int, val maxHp: Int, val enemy: Boolean = false,
    val range: Int = 1, val characterClass: DemoClass = DemoClass.ENEMY,
    val dexterity: Int = 0, val armor: Int = 12, val attackBonus: Int = 3,
    val damage: DamageDice = DamageDice(1, 6, 1),
    val bandages: Int = if (enemy) 0 else 1,
)

@kotlinx.serialization.Serializable
data class DemoInitiative(val fighterId: String, val die: Int, val dexterity: Int) {
    val total get() = die + dexterity
}

@kotlinx.serialization.Serializable
data class DemoAttackResult(
    val actor: String, val target: String, val attackDice: List<Int>, val bonus: Int,
    val armor: Int, val hit: Boolean, val critical: Boolean,
    val damageDice: List<Int> = emptyList(), val damageSides: Int = 0, val damageBonus: Int = 0,
) {
    val die get() = attackDice.max()
    val total get() = die + bonus
    val damage get() = if (hit) maxOf(0, damageDice.sum() + damageBonus) else 0
    val description: String get() {
        val roll = if (attackDice.size == 1) "$die" else "max(${attackDice.joinToString(", ")})=$die"
        val attack = "$actor → $target: d20 $roll + $bonus = $total против КЗ $armor."
        if (!hit) return "$attack Промах${if (die == 1) " (натуральная 1)" else ""}; урон 0."
        return "$attack ${if (critical) "Критическое попадание!" else "Попадание."} Урон ${damageDice.size}d$damageSides${if (damageBonus > 0) "+$damageBonus" else ""}: ${damageDice.joinToString(" + ")}${if (damageBonus > 0) " + $damageBonus" else ""} = $damage."
    }
}

@kotlinx.serialization.Serializable
enum class DemoAction(val title: String, val symbol: String) {
    MOVE("Движение", "↗"), ATTACK("Атака", "⚔"), SKILL("Приём", "✦"),
    HELP("Помощь", "+"), GUARD("Защита", "◇"), BANDAGE("Повязка", "✚"),
}

@kotlinx.serialization.Serializable
data class DemoPreview(
    val valid: Boolean, val title: String, val detail: String, val cost: String,
    val path: List<Tile> = emptyList(), val chance: Double? = null,
)

@kotlinx.serialization.Serializable
data class CombatDemo(
    val fighters: List<DemoFighter> = initialFighters(),
    val initiative: List<DemoInitiative> = fighters.map { DemoInitiative(it.id, 10, it.dexterity) }
        .sortedWith(compareByDescending<DemoInitiative> { it.total }.thenByDescending { it.dexterity }),
    val activeId: String = initiative.first().fighterId, val round: Int = 1,
    val moved: Boolean = false, val acted: Boolean = false,
    val spentSkills: Set<String> = emptySet(), val guarded: Set<String> = emptySet(),
    val assisted: Set<String> = emptySet(), val lastAttack: DemoAttackResult? = null,
    val raisedHeroes: Set<String> = emptySet(),
    val log: List<String> = emptyList(), val lastResult: String = "Инициатива определяет порядок героев и противников.",
    val diceEvents: List<DiceEvent> = emptyList(),
    val events: List<CombatEvent> = emptyList(),
) {
    val active get() = fighters.first { it.id == activeId }
    val orderedFighters get() = initiative.map { roll -> fighters.first { it.id == roll.fighterId } }
    val finished get() = fighters.none { it.enemy && it.hp > 0 } || fighters.none { !it.enemy && it.hp > 0 }
    val victory get() = fighters.none { it.enemy && it.hp > 0 }
    val playerTurn get() = !finished && !active.enemy && active.hp > 0
    // Downed heroes reserve their tile so healing never revives them inside another fighter.
    fun occupant(tile: Tile) = fighters.firstOrNull { it.tile == tile && (it.hp > 0 || !it.enemy) }
    fun recoveryStatus(fighter: DemoFighter) =
        if (fighter.id in raisedHeroes) "Уже поднимали в этом бою" else "Поднятие доступно"
    fun armorOf(fighter: DemoFighter, from: Tile): Int = fighter.armor +
        (if (fighter.id in guarded) 2 else 0) +
        (if (fighter.tile in coverTiles && from.distance(fighter.tile) > 1) 2 else 0)

    fun pathTo(target: Tile, limit: Int = 3): List<Tile> {
        if (target !in tiles || target in walls || occupant(target) != null) return emptyList()
        val paths = mutableMapOf(active.tile to listOf(active.tile))
        val queue = mutableListOf(active.tile)
        var index = 0
        while (index < queue.size) {
            val tile = queue[index++]
            val path = paths.getValue(tile)
            if (path.size > limit) continue
            for (next in tiles.filter { it.distance(tile) == 1 }) {
                if (next in paths || next in walls || occupant(next) != null) continue
                val nextPath = path + next
                if (next == target) return nextPath
                paths[next] = nextPath
                queue += next
            }
        }
        return emptyList()
    }

    val skillName get() = when (active.characterClass) {
        DemoClass.WARRIOR -> "Второе дыхание"
        DemoClass.MAGE -> "Огненный всплеск"
        DemoClass.ROGUE -> "Точный выстрел"
        DemoClass.BARD -> "Ободряющее слово"
        DemoClass.ENEMY -> "Атака"
    }
    val selfSkill get() = active.characterClass == DemoClass.WARRIOR
    fun needsTarget(action: DemoAction) = action != DemoAction.GUARD && !(action == DemoAction.SKILL && selfSkill)
    private fun attackDamage(action: DemoAction) = when {
        action != DemoAction.SKILL -> active.damage
        active.characterClass == DemoClass.MAGE -> DamageDice(2, 6)
        active.characterClass == DemoClass.ROGUE -> DamageDice(2, 6, 2)
        else -> active.damage
    }
    private fun advantage(action: DemoAction) = activeId in assisted ||
        (action == DemoAction.SKILL && active.characterClass == DemoClass.ROGUE)

    fun preview(action: DemoAction, target: Tile?): DemoPreview {
        fun no(reason: String) = DemoPreview(false, if (action == DemoAction.SKILL) skillName else action.title, reason, "Ресурсы не расходуются")
        if (finished) return no("Бой завершён. Можно начать заново.")
        if (active.enemy) return no("Противник ходит автоматически. Дождитесь своего героя.")
        if (active.hp == 0) return no("Герой выбыл и не может действовать.")
        if (action == DemoAction.MOVE) {
            if (moved) return no("Перемещение в этом ходу уже использовано.")
            if (target == null) return no("Выберите подсвеченную клетку — до 3 шагов.")
            val path = pathTo(target)
            if (target in walls) return no("Здесь каменная преграда — пройти нельзя.")
            if (occupant(target) != null) return no("Клетка занята существом.")
            if (path.isEmpty()) return no("Нет свободного маршрута в пределах 3 шагов.")
            return DemoPreview(true, "Перейти на ${target.label}", "Маршрут: ${path.joinToString(" → ") { it.label }}",
                "1 перемещение · ${path.size - 1}/3 шага", path)
        }
        if (acted) return no("Действие уже использовано. Можно двигаться или завершить ход.")
        if (action == DemoAction.BANDAGE && active.bandages == 0) return no("Повязок не осталось.")
        if (action == DemoAction.GUARD) return DemoPreview(true, "Прикрыться", "+2 к КЗ до начала следующего собственного хода. Упрощённая защита.", "1 действие")
        if (action == DemoAction.SKILL && activeId in spentSkills) return no("Приём уже использован в этом бою.")
        if (action == DemoAction.SKILL && selfSkill) {
            if (active.hp == active.maxHp) return no("Здоровье полное. Второе дыхание пока не нужно.")
            return DemoPreview(true, skillName, "Восстановить себе 1d10+2 здоровья (3–12), до максимума ${active.maxHp}.", "1 действие · 1 раз за бой")
        }
        if (target == null) return no(when {
            action == DemoAction.HELP -> "Выберите союзника рядом: его следующая атака получит преимущество."
            action == DemoAction.BANDAGE -> "Выберите себя или соседнего союзника. Повязка восстанавливает 4 здоровья; поднять героя можно раз за бой."
            action == DemoAction.SKILL && active.characterClass == DemoClass.BARD -> "Выберите раненого героя в 3 клетках, включая себя. Лечение 1d6+2."
            else -> "Выберите подсвеченного противника."
        })
        val fighter = occupant(target) ?: return no("На этой клетке нет доступной цели.")
        if (action == DemoAction.BANDAGE) {
            if (fighter.enemy) return no("Повязка доступна только героям отряда.")
            if (fighter.hp == 0 && fighter.id in raisedHeroes) return no("${fighter.name}: уже поднимали в этом бою. Повторное поднятие недоступно до конца боя.")
            if (active.tile.distance(target) > 1) return no("Для повязки нужно встать рядом с союзником или выбрать себя.")
            if (fighter.hp == fighter.maxHp) return no("У героя полное здоровье.")
            val hp = minOf(fighter.maxHp, fighter.hp + bandageHealing)
            return DemoPreview(true, "${if (fighter.hp == 0) "Поднять" else "Перевязать"}: ${fighter.name}",
                "Здоровье: ${fighter.hp} → $hp/${fighter.maxHp} (+${hp - fighter.hp}). ${recoveryStatus(fighter)}.${if (fighter.hp == 0) " Единственное поднятие в этом бою будет использовано." else ""}",
                "1 основное действие · 1 повязка (осталось ${active.bandages})")
        }
        if (fighter.hp == 0) return no("Герой выбыл. Используйте повязку, если поднятие ещё доступно.")
        if (action == DemoAction.HELP) {
            if (fighter.enemy || fighter.id == activeId) return no("Нужен другой союзник.")
            if (active.tile.distance(target) > 1) return no("Для помощи нужно встать рядом.")
            if (fighter.id in assisted) return no("Союзник уже получил помощь; преимущества не складываются.")
            return DemoPreview(true, "Помочь: ${fighter.name}", "Следующая атака: 2d20, выбирается лучший результат. Помощь не лечит.", "1 действие")
        }
        if (action == DemoAction.SKILL && active.characterClass == DemoClass.BARD) {
            if (fighter.enemy) return no("Лечение доступно только героям отряда.")
            if (active.tile.distance(target) > 3 || !clearSight(active.tile, target)) return no("Нужен видимый герой не дальше 3 клеток.")
            if (fighter.hp == fighter.maxHp) return no("У героя полное здоровье.")
            return DemoPreview(true, "$skillName: ${fighter.name}", "Лечение 1d6+2 (3–8), до максимума ${fighter.maxHp}.", "1 действие · 1 раз за бой")
        }
        if (!fighter.enemy) return no("Выберите противника.")
        if (active.tile.distance(target) > active.range) return no("Цель далеко. Дальность: ${active.range} кл. Сначала переместитесь.")
        if (!clearSight(active.tile, target)) return no("Каменная преграда закрывает линию атаки.")
        val damage = attackDamage(action)
        val armor = armorOf(fighter, active.tile)
        val advantage = advantage(action)
        val chance = hitChance(active.attackBonus, armor, advantage)
        val tenths = (chance * 1000).roundToInt()
        val percent = "${tenths / 10}${if (tenths % 10 == 0) "" else ".${tenths % 10}"}%"
        return DemoPreview(true, "${if (action == DemoAction.SKILL) skillName else "Атака"}: ${fighter.name}",
            "${if (advantage) "2d20, лучший" else "d20"} + ${active.attackBonus} против КЗ $armor · шанс $percent\nУрон ${damage.formula} (${damage.minimum}–${damage.maximum}); на 20 — двойные кости.${if (armor > fighter.armor) " КЗ включает укрытие/защиту." else ""}",
            if (action == DemoAction.SKILL) "1 действие · приём 1 раз за бой, даже при промахе" else "1 действие", chance = chance)
    }

    fun execute(action: DemoAction, target: Tile?, dice: DiceRoller = randomDice): CombatDemo {
        if (!preview(action, target).valid) return this
        if (action == DemoAction.MOVE) return copy(
            events = events + CombatEvent(CombatEventKind.MOVE, active, title = "Перемещение", logIndex = log.size,
                fighters = fighters, path = pathTo(requireNotNull(target))),
            fighters = fighters.map { if (it.id == activeId) it.copy(tile = requireNotNull(target)) else it }, moved = true)
            .record("${active.name}: переход на ${target!!.label}.")
        if (action == DemoAction.GUARD) return copy(acted = true, guarded = guarded + activeId)
            .copy(events = events + CombatEvent(CombatEventKind.GUARD, active, active, "Защита", log.size, fighters))
            .record("${active.name}: защита +2 КЗ до следующего собственного хода.")
        if (action == DemoAction.SKILL && selfSkill) return heal(active, DamageDice(1, 10, 2), dice)
        val victim = requireNotNull(occupant(requireNotNull(target)))
        if (action == DemoAction.BANDAGE) {
            val hp = minOf(victim.maxHp, victim.hp + bandageHealing)
            return copy(acted = true,
                events = events + CombatEvent(CombatEventKind.BANDAGE, active, victim, "Лечебная повязка",
                    log.size, fighters, healthAfter = hp),
                raisedHeroes = if (victim.hp == 0) raisedHeroes + victim.id else raisedHeroes,
                fighters = fighters.map {
                    it.copy(hp = if (it.id == victim.id) hp else it.hp,
                        bandages = if (it.id == activeId) it.bandages - 1 else it.bandages)
                }).record("${active.name}: повязка → ${victim.name}, здоровье ${victim.hp} → $hp; потрачены действие и повязка.${if (victim.hp == 0) " Поднятие в этом бою использовано." else ""}")
        }
        if (action == DemoAction.HELP) return copy(acted = true, assisted = assisted + victim.id)
            .copy(events = events + CombatEvent(CombatEventKind.HELP, active, victim, "Помощь", log.size, fighters))
            .record("${active.name} помогает ${victim.name}: преимущество на следующую атаку.")
        if (action == DemoAction.SKILL && active.characterClass == DemoClass.BARD) return heal(victim, DamageDice(1, 6, 2), dice)
        return attack(victim, attackDamage(action), advantage(action), dice,
            if (action == DemoAction.SKILL) skillName else "Атака").copy(
            spentSkills = if (action == DemoAction.SKILL) spentSkills + activeId else spentSkills)
    }

    private fun heal(victim: DemoFighter, formula: DamageDice, dice: DiceRoller): CombatDemo {
        val rolls = List(formula.count) { roll(dice, formula.sides) }
        val total = rolls.sum() + formula.bonus
        val recovered = minOf(victim.maxHp - victim.hp, total)
        return copy(acted = true, spentSkills = spentSkills + activeId,
            events = events + CombatEvent(CombatEventKind.HEAL, active, victim, skillName, log.size, fighters,
                rolls = listOf(DiceEvent(skillName, active.name, rolls, formula.sides, formula.bonus)),
                healthAfter = victim.hp + recovered),
            diceEvents = diceEvents + DiceEvent(skillName, active.name, rolls, formula.sides, formula.bonus,
                outcome = "Лечение", detail = "${victim.name}: восстановлено $recovered здоровья", logIndex = log.size),
            fighters = fighters.map { if (it.id == victim.id) it.copy(hp = it.hp + recovered) else it })
            .record("${active.name} — $skillName → ${victim.name}: ${formula.formula}, выпало ${rolls.joinToString(" + ")} + ${formula.bonus} = $total; восстановлено $recovered здоровья.")
    }

    private fun attack(victim: DemoFighter, damage: DamageDice, advantage: Boolean, dice: DiceRoller, title: String = "Атака"): CombatDemo {
        val attackDice = List(if (advantage) 2 else 1) { roll(dice, 20) }
        val chosen = attackDice.max()
        val armor = armorOf(victim, active.tile)
        val hit = hits(chosen, active.attackBonus, armor)
        val critical = chosen == 20
        val damageRolls = if (hit) List(damage.count * if (critical) 2 else 1) { roll(dice, damage.sides) } else emptyList()
        val result = DemoAttackResult(active.name, victim.name, attackDice, active.attackBonus, armor, hit, critical,
            damageRolls, damage.sides, damage.bonus)
        val hp = maxOf(0, victim.hp - result.damage)
        return copy(acted = true, assisted = assisted - activeId, lastAttack = result,
            events = events + CombatEvent(CombatEventKind.ATTACK, active, victim,
                title, log.size, fighters,
                rolls = listOf(DiceEvent("Атака", active.name, attackDice, 20, active.attackBonus, armor, hit,
                    if (hit) if (critical) "Критическое попадание" else "Попадание" else "Промах", best = advantage)) +
                    if (hit) listOf(DiceEvent("Урон", active.name, damageRolls, damage.sides, damage.bonus,
                        outcome = "${result.damage} урона")) else emptyList(),
                hit = hit, healthAfter = hp),
            diceEvents = diceEvents + DiceEvent("Атака → ${victim.name}", active.name, attackDice, 20,
                active.attackBonus, armor, hit,
                if (hit) if (critical) "Критическое попадание" else "Попадание" else "Промах",
                best = advantage, detail = "Против класса защиты $armor", logIndex = log.size) +
                if (hit) listOf(DiceEvent("Урон → ${victim.name}", active.name, damageRolls, damage.sides,
                    damage.bonus, outcome = "${result.damage} урона", logIndex = log.size)) else emptyList(),
            fighters = fighters.map { if (it.id == victim.id) it.copy(hp = hp) else it })
            .record(result.description + if (hp == 0) " ${victim.name} выведен из боя." else "")
            .recoverAfterVictory()
    }

    private fun recoverAfterVictory(): CombatDemo {
        if (!victory) return this
        return copy(fighters = fighters.map {
            if (!it.enemy) it.copy(hp = maxOf(it.hp, minOf(it.maxHp, victoryHealth))) else it
        }).record("Победа! Все выжившие герои приходят в себя: здоровье не ниже $victoryHealth. Повязки не пополняются.")
    }

    fun endTurn(): CombatDemo = if (playerTurn) advanceTurn() else this

    private fun advanceTurn(): CombatDemo {
        if (finished) return this
        val current = initiative.indexOfFirst { it.fighterId == activeId }
        val offset = (1..initiative.size).first { step -> fighters.first { it.id == initiative[(current + step) % initiative.size].fighterId }.hp > 0 }
        val next = fighters.first { it.id == initiative[(current + offset) % initiative.size].fighterId }
        val newRound = round + (current + offset) / initiative.size
        return copy(activeId = next.id, round = newRound, moved = false, acted = false, guarded = guarded - next.id,
            log = log + "${if (newRound != round) "Раунд $newRound. " else ""}Ход ${next.name}.")
    }

    fun enemyTurn(dice: DiceRoller = randomDice): CombatDemo {
        if (finished || !active.enemy) return this
        val heroes = fighters.filter { !it.enemy && it.hp > 0 }
        val positions = listOf(active.tile) + tiles.filter { pathTo(it).isNotEmpty() }
        val destination = positions.minWith(compareBy<Tile> { position ->
            if (heroes.any { position.distance(it.tile) <= active.range && clearSight(position, it.tile) }) 0 else 1
        }.thenBy { position -> heroes.minOf { position.distance(it.tile) } }
            .thenBy { if (it == active.tile) 0 else pathTo(it).size })
        var next = this
        if (destination != active.tile) next = copy(moved = true,
            events = events + CombatEvent(CombatEventKind.MOVE, active, title = "Перемещение", logIndex = log.size,
                fighters = fighters, path = pathTo(destination)),
            fighters = fighters.map { if (it.id == activeId) it.copy(tile = destination) else it })
            .record("${active.name}: переход ${active.tile.label} → ${destination.label}.")
        val victim = heroes.filter { next.active.tile.distance(it.tile) <= next.active.range && clearSight(next.active.tile, it.tile) }
            .minByOrNull { next.active.tile.distance(it.tile) }
        next = if (victim != null) next.attack(victim, active.damage, false, dice)
            else next.record("${active.name} не может атаковать: нет видимой цели в пределах дальности.")
        return next.advanceTurn()
    }

    private fun record(message: String) = copy(log = log + message, lastResult = message)

    companion object {
        const val columns = 6
        const val rows = 5
        const val bandageHealing = 4
        const val victoryHealth = 4
        val tiles = (0 until rows).flatMap { y -> (0 until columns).map { x -> Tile(x, y) } }
        val walls = setOf(Tile(2, 1), Tile(2, 2), Tile(3, 3))
        val coverTiles = setOf(Tile(4, 1), Tile(1, 3))
        val randomDice = DiceRoller { sides -> Random.nextInt(1, sides + 1) }
        private fun roll(dice: DiceRoller, sides: Int) = dice.roll(sides).also { require(it in 1..sides) { "Invalid d$sides result" } }
        fun hits(die: Int, bonus: Int, armor: Int) = die == 20 || (die != 1 && die + bonus >= armor)
        fun hitChance(bonus: Int, armor: Int, advantage: Boolean): Double {
            val normal = (1..20).count { hits(it, bonus, armor) } / 20.0
            return if (advantage) 1 - (1 - normal) * (1 - normal) else normal
        }
        fun start(dice: DiceRoller = randomDice, fighters: List<DemoFighter> = initialFighters()): CombatDemo {
            val rolls = fighters.map { DemoInitiative(it.id, roll(dice, 20), it.dexterity) }
                .sortedWith(compareByDescending<DemoInitiative> { it.total }.thenByDescending { it.dexterity })
            val messages = rolls.map { result -> "${fighters.first { it.id == result.fighterId }.name}: инициатива d20 ${result.die} + ${result.dexterity} = ${result.total}." }
            val active = rolls.first { result -> fighters.first { it.id == result.fighterId }.hp > 0 }.fighterId
            return CombatDemo(fighters = fighters, initiative = rolls, activeId = active,
                events = listOf(CombatEvent(CombatEventKind.INITIATIVE, fighters.first { !it.enemy },
                    title = "Инициатива", logIndex = 0, fighters = fighters, initiative = rolls)),
                diceEvents = rolls.mapIndexed { index, result -> DiceEvent("Инициатива",
                    fighters.first { it.id == result.fighterId }.name, listOf(result.die), 20,
                    result.dexterity, outcome = "Место в очереди: ${index + 1}", logIndex = index) },
                log = messages + "Ход ${fighters.first { it.id == active }.name}.")
        }
        fun clearSight(from: Tile, to: Tile): Boolean {
            val steps = maxOf(abs(from.x - to.x), abs(from.y - to.y)) * 4
            return (1 until steps).none { step ->
                val x = kotlin.math.round(from.x + (to.x - from.x) * step.toDouble() / steps).toInt()
                val y = kotlin.math.round(from.y + (to.y - from.y) * step.toDouble() / steps).toInt()
                Tile(x, y) in walls
            }
        }
        private fun initialFighters() = listOf(
            DemoFighter("bran", "Бран", "Воин", "Б", Tile(1, 2), 14, 14, characterClass = DemoClass.WARRIOR,
                armor = 15, attackBonus = 4, damage = DamageDice(1, 8, 2)),
            DemoFighter("mira", "Мира", "Маг", "М", Tile(0, 3), 9, 12, range = 4, characterClass = DemoClass.MAGE,
                dexterity = 1, armor = 12, attackBonus = 4, damage = DamageDice(1, 8)),
            DemoFighter("rin", "Рин", "Плут", "Р", Tile(1, 4), 13, 13, range = 4, characterClass = DemoClass.ROGUE,
                dexterity = 2, armor = 14, attackBonus = 4, damage = DamageDice(1, 6, 2)),
            DemoFighter("lada", "Лада", "Бард", "Л", Tile(0, 1), 12, 12, range = 3, characterClass = DemoClass.BARD,
                dexterity = 1, armor = 13, attackBonus = 3, damage = DamageDice(1, 6, 1)),
            DemoFighter("guard", "Страж", "Противник", "С", Tile(4, 1), 18, 18, true, armor = 13),
            DemoFighter("raider", "Налётчик", "Противник", "Н", Tile(4, 3), 14, 14, true, range = 4, dexterity = 1, armor = 12),
        )
    }
}
