package ru.markn.dnd.adventure

import ru.markn.dnd.combat.*

/** Uses the same class abilities as the teaching screen, with the actual selected party. */
internal fun AdventureState.createEncounter(dice: DiceRoller): CombatDemo {
    val positions = listOf(Tile(1, 2), Tile(0, 3), Tile(1, 4), Tile(0, 1))
    val heroes = players.mapIndexed { index, player ->
        val hero = player.hero
        val role = when (hero.profile?.characterClass ?: CharacterClass.WARRIOR) {
            CharacterClass.WARRIOR -> DemoClass.WARRIOR
            CharacterClass.ROGUE -> DemoClass.ROGUE
            CharacterClass.MAGE -> DemoClass.MAGE
            CharacterClass.BARD -> DemoClass.BARD
        }
        val ability = when (role) {
            DemoClass.WARRIOR -> Ability.STRENGTH
            DemoClass.MAGE -> Ability.INTELLIGENCE
            DemoClass.BARD -> Ability.CHARISMA
            else -> Ability.DEXTERITY
        }
        val bonus = hero.modifier(ability)
        DemoFighter(player.id, hero.name, hero.archetype, hero.name.take(1), positions[index], hero.health, hero.maxHealth,
            range = if (role == DemoClass.WARRIOR) 1 else 4, characterClass = role,
            dexterity = hero.modifier(Ability.DEXTERITY), armor = if (role == DemoClass.WARRIOR) 15 else 12 + hero.modifier(Ability.DEXTERITY),
            attackBonus = 2 + bonus, damage = DamageDice(1, if (role == DemoClass.WARRIOR || role == DemoClass.MAGE) 8 else 6, bonus),
            bandages = hero.inventory.count { it == ArchiveDemo.BANDAGE })
    }
    // Small encounter scales by party size, while preserving all prototype combat rules.
    val hp = 6 + players.size * 2
    val foes = listOf(
        DemoFighter("archive-guard", "Вышибала", "Налётчик", "В", Tile(4, 1), hp + 2, hp + 2, true,
            armor = 12, attackBonus = 2, damage = DamageDice(1, 4, 1)),
        DemoFighter("archive-raider", "Стрелок", "Налётчик", "С", Tile(4, 3), hp, hp, true,
            range = 4, armor = 11, attackBonus = 2, damage = DamageDice(1, 4)),
    )
    return CombatDemo.start(dice, heroes + foes)
}
