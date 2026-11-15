package ru.markn.dnd.adventure

import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
enum class CheckDomain(val title: String, val abilities: Set<Ability>) {
    FORCE("Физическая сила", setOf(Ability.STRENGTH)),
    ENDURANCE("Физическая нагрузка", setOf(Ability.CONSTITUTION)),
    LOCKS("Вскрытие замков", setOf(Ability.DEXTERITY, Ability.INTELLIGENCE)),
    TRAPS("Механические ловушки", setOf(Ability.DEXTERITY, Ability.INTELLIGENCE)),
    MAGIC("Магические явления", setOf(Ability.INTELLIGENCE)),
    DEVICES("Устройства и письменные загадки", setOf(Ability.INTELLIGENCE)),
    PERSUASION("Убеждение сотрудничать", setOf(Ability.CHARISMA)),
    PERFORMANCE("Выступление перед публикой", setOf(Ability.CHARISMA)),
    OBSERVATION("Зрение и слух", setOf(Ability.WISDOM)),
    CRAFT("Каменные и металлические конструкции", setOf(Ability.INTELLIGENCE)),
    BALANCE("Равновесие и тесные проходы", setOf(Ability.DEXTERITY)),
    MILITARY("Военные порядки и тактика", setOf(Ability.INTELLIGENCE, Ability.WISDOM)),
    REPAIR("Ремонт", setOf(Ability.INTELLIGENCE)),
    DOCUMENTS("Поиск и сопоставление документов", setOf(Ability.INTELLIGENCE)),
    TRADE("Торг и условия обмена", setOf(Ability.CHARISMA)),
    SEAMANSHIP("Узлы, такелаж и лодки", setOf(Ability.DEXTERITY, Ability.WISDOM)),
    DISTRACTION("Отвлекающее представление", setOf(Ability.CHARISMA)),
}

@Serializable
enum class CharacterClass(val title: String, val domains: Set<CheckDomain>) {
    WARRIOR("Воин", setOf(CheckDomain.FORCE, CheckDomain.ENDURANCE)),
    ROGUE("Плут", setOf(CheckDomain.LOCKS, CheckDomain.TRAPS)),
    MAGE("Маг-исследователь", setOf(CheckDomain.MAGIC, CheckDomain.DEVICES)),
    BARD("Бард", setOf(CheckDomain.PERSUASION, CheckDomain.PERFORMANCE)),
}

@Serializable
enum class CharacterRace(val title: String, val talent: String, val domains: Set<CheckDomain>) {
    HUMAN("Человек", "Общий язык", setOf(CheckDomain.PERSUASION)),
    ELF("Эльф", "Острые чувства", setOf(CheckDomain.OBSERVATION)),
    DWARF("Дварф", "Знаток ремесла", setOf(CheckDomain.CRAFT)),
    HALFLING("Полурослик", "Ловкий шаг", setOf(CheckDomain.BALANCE)),
}

@Serializable
enum class CharacterBackground(val title: String, val domain: CheckDomain) {
    SOLDIER("Солдат", CheckDomain.MILITARY),
    ARTISAN("Ремесленник", CheckDomain.REPAIR),
    ARCHIVIST("Архивист", CheckDomain.DOCUMENTS),
    MERCHANT("Торговец", CheckDomain.TRADE),
    SAILOR("Моряк", CheckDomain.SEAMANSHIP),
    ARTIST("Артист", CheckDomain.DISTRACTION),
}

/** Persistent identity and build. No session health, location, items or expended talents. */
@Serializable
data class CharacterProfile(
    val name: String,
    val characterClass: CharacterClass,
    val race: CharacterRace,
    val background: CharacterBackground,
    val scores: Map<Ability, Int>,
    val description: String = "",
    val avatarId: String? = null,
) {
    fun validationError(): String? = when {
        avatarId != null && avatarId !in PreparedAvatars.ids -> "Выберите аватар из подготовленного набора."
        name != name.trim() || name.length !in 1..24 || name.any { it.isISOControl() } -> "Имя героя: от 1 до 24 символов без управляющих знаков."
        description.length > 240 || description.any { it.isISOControl() && it != '\n' } -> "Описание героя: до 240 символов."
        scores.keys != Ability.entries.toSet() || scores.values.sorted() != STANDARD_SCORES.sorted() -> "Распределите числа 15, 14, 13, 12, 10, 8 по одному на каждую характеристику."
        else -> null
    }

    fun modifier(ability: Ability): Int = floor((scores.getValue(ability) - 10) / 2.0).toInt()
    fun hero(scenario: ScenarioDefinition): Hero {
        require(validationError() == null) { "Invalid character profile" }
        val health = 12 + modifier(Ability.CONSTITUTION)
        return Hero(name, characterClass.title, health, health,
            Ability.entries.associateWith(::modifier), scenario.startingItems, this)
    }

    companion object { val STANDARD_SCORES = listOf(15, 14, 13, 12, 10, 8) }
}

/** Stable, bundled portrait IDs; numeric ability scores never restrict appearance. */
object PreparedAvatars {
    fun id(race: CharacterRace, characterClass: CharacterClass): String =
        "${race.name.lowercase()}_${characterClass.name.lowercase()}"
    val ids = CharacterRace.entries.flatMap { race -> CharacterClass.entries.map { id(race, it) } }.toSet()
}

object ReadyCharacters {
    private fun scores(vararg values: Int) = Ability.entries.zip(values.toList()).toMap()
    val rin = CharacterProfile("Рин", CharacterClass.ROGUE, CharacterRace.ELF, CharacterBackground.SAILOR,
        scores(12, 15, 13, 10, 14, 8), "Любит разведку, высоту и неожиданные пути.")
    val mira = CharacterProfile("Мира", CharacterClass.MAGE, CharacterRace.DWARF, CharacterBackground.ARCHIVIST,
        scores(8, 12, 10, 15, 14, 13), "Изучает устройства, загадки и старые документы.")
    val bran = CharacterProfile("Бран", CharacterClass.WARRIOR, CharacterRace.HUMAN, CharacterBackground.SOLDIER,
        scores(15, 10, 14, 8, 12, 13), "Надёжный товарищ, привыкший встречать опасность первым.")
    val lada = CharacterProfile("Лада", CharacterClass.BARD, CharacterRace.HALFLING, CharacterBackground.ARTIST,
        scores(8, 14, 13, 10, 12, 15), "Поддерживает друзей музыкой и всегда находит выходку на случай опасности.")
    val all = listOf(rin, mira, bran, lada)
}

@Serializable
data class CheckBonuses(val ability: Int, val expertise: Int, val racial: Int) {
    val total: Int get() = ability + expertise + racial
}

object CharacterRules {
    fun canUseTalent(state: AdventureState, check: ProposedCheck): Boolean = canUseTalent(state.activePlayer.hero, check) &&
        (state.activePlayer.hero.profile?.race != CharacterRace.HUMAN ||
            state.scenario.sites.getValue(state.location).npcId?.let { it !in state.scene.metNpcs[state.activePlayer.id].orEmpty() } == true)

    fun bonuses(hero: Hero, check: ProposedCheck, useTalent: Boolean = false): CheckBonuses {
        val profile = hero.profile
        val domains = check.domains.filter { check.ability in it.abilities }.toSet()
        val expertise = if (profile != null && (profile.characterClass.domains.any { it in domains } ||
                profile.background.domain in domains)) 1 else 0
        val racial = if (useTalent && canUseTalent(hero, check)) 2 else 0
        return CheckBonuses(hero.modifier(check.ability), expertise, racial)
    }

    fun canUseTalent(hero: Hero, check: ProposedCheck): Boolean = !hero.racialTalentUsed &&
        hero.profile?.race?.domains?.any { it in check.domains && check.ability in it.abilities } == true
}
