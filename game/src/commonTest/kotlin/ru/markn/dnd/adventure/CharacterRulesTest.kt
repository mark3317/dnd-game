package ru.markn.dnd.adventure

import kotlin.test.*
import ru.markn.dnd.network.*

class CharacterRulesTest {
    @Test fun humanTalentRequiresANewNpcAndConversationRecordsAcquaintancePerHero() {
        val engine = AdventureEngine(DiceRoller { 1 })
        val human = ReadyCharacters.bran.hero(FloodedArchive.definition)
        val start = engine.newGame(players = listOf(Player("p", "Игрок", human, FloodedArchive.YARD)), scenarioId = FloodedArchive.ID)
            .copy(scene = SceneState(setOf(FloodedArchive.INDEX_READ)))
        val requested = engine.submit(start, "p", start.turnId, "Договориться о копиях")
        val check = requested.pendingCheck!!
        assertTrue(CharacterRules.canUseTalent(requested, check.plan!!.check!!))
        val failed = engine.roll(requested, "p", check.id)
        assertFalse(failed.activePlayer.hero.racialTalentUsed)
        val repeat = engine.submit(failed, "p", failed.turnId, "Договориться о копиях")
        assertFalse(CharacterRules.canUseTalent(repeat, repeat.pendingCheck!!.plan!!.check!!))
        assertEquals(repeat, engine.roll(repeat, "p", repeat.pendingCheck!!.id, true))
        val talked = engine.submit(start, "p", start.turnId, "Поговорить")
        assertEquals(setOf("COURIER"), talked.scene.metNpcs["p"])
        assertFalse(CharacterRules.canUseTalent(talked, check.plan!!.check!!))
        val other = talked.copy(scene = talked.scene.copy(metNpcs = mapOf("other" to setOf("COURIER"))))
        assertTrue(CharacterRules.canUseTalent(other, check.plan!!.check!!))
    }
    @Test fun presetsHaveExactlyTheSameBuildLimitsAsCustomCharacters() {
        for (profile in ReadyCharacters.all) {
            assertNull(profile.validationError())
            val custom = profile.copy(name = "Свой герой")
            for (scenario in Scenarios.all) {
                val hero = profile.hero(scenario)
                assertEquals(custom.hero(scenario).copy(name = hero.name, profile = profile), hero)
                assertEquals(12 + profile.modifier(Ability.CONSTITUTION), hero.maxHealth)
                assertEquals(scenario.startingItems, hero.inventory)
                assertFalse(hero.racialTalentUsed)
            }
        }
        val profile = ReadyCharacters.rin
        assertNotNull(profile.copy(scores = profile.scores + (Ability.STRENGTH to 20)).validationError())
        assertNotNull(profile.copy(scores = profile.scores - Ability.WISDOM).validationError())
        assertNotNull(profile.copy(name = "\n").validationError())
        assertNotNull(profile.copy(description = "x".repeat(241)).validationError())
        assertEquals(-1, ReadyCharacters.bran.modifier(Ability.INTELLIGENCE))
    }

    @Test fun everyCombinationIsBoundedAndClassAndBackgroundNeverStack() {
        for (clazz in CharacterClass.entries) for (race in CharacterRace.entries) for (background in CharacterBackground.entries) {
            val hero = ReadyCharacters.mira.copy(characterClass = clazz, race = race, background = background).hero(Lighthouse.definition)
            for (ability in Ability.entries) {
                val domains = CheckDomain.entries.filter { ability in it.abilities }.toSet()
                val bonus = CharacterRules.bonuses(hero, ProposedCheck(ability, 12, domains), true)
                assertTrue(bonus.expertise in 0..1)
                assertTrue(bonus.racial in setOf(0, 2))
                assertTrue(bonus.total in -1..5)
            }
        }
        val hero = ReadyCharacters.mira.copy(background = CharacterBackground.ARTISAN).hero(Lighthouse.definition)
        assertEquals(3, CharacterRules.bonuses(hero, ProposedCheck(Ability.INTELLIGENCE, 12,
            setOf(CheckDomain.DEVICES, CheckDomain.REPAIR))).total)
    }

    @Test fun talentIsOptInOnlyConsumedByAValidRollAndCannotBeUsedTwice() {
        val engine = AdventureEngine(DiceRoller { 1 })
        val profile = ReadyCharacters.rin.copy(race = CharacterRace.HALFLING)
        val player = Player("p", "Игрок", profile.hero(Lighthouse.definition), Lighthouse.SHORE)
        val start = engine.newGame(players = listOf(player))
        val pending = engine.submit(start, "p", start.turnId, Lighthouse.CLIMB.text)
        val id = pending.pendingCheck!!.id
        assertFalse(engine.cancelCheck(pending, "p", id).activePlayer.hero.racialTalentUsed)
        assertEquals(pending, engine.roll(pending, "other", id, true))
        assertEquals(pending, engine.roll(pending, "p", id.copy(actionId = 999), true))
        val rolled = engine.roll(pending, "p", id, true)
        assertTrue(rolled.activePlayer.hero.racialTalentUsed)
        assertFalse(rolled.journal.mapNotNull { it.roll }.single().succeeded)
        assertEquals(4, rolled.journal.mapNotNull { it.roll }.single().modifier)
        assertEquals(rolled, engine.roll(rolled, "p", id, true))
        val next = engine.submit(rolled, "p", rolled.turnId, Lighthouse.CLIMB.text)
        assertEquals(next, engine.roll(next, "p", next.pendingCheck!!.id, true))
        val withoutTalent = engine.roll(pending, "p", id)
        assertFalse(withoutTalent.activePlayer.hero.racialTalentUsed)
        val reset = profile.hero(FloodedArchive.definition)
        assertFalse(reset.racialTalentUsed)
        assertEquals(reset.maxHealth, reset.health)
    }

    @Test fun freePlanCannotInventBonusDomainsOrUseAnIncompatibleAbility() {
        val state = AdventureEngine().newGame(scenarioId = FloodedArchive.ID)
        val plan = state.scenario.actions.first { it.id == "OPEN_DOOR" }.plan!!
        assertNull(SceneRules.validate(state, plan))
        assertEquals("invalid_check_domains", SceneRules.validate(state,
            plan.copy(check = plan.check!!.copy(domains = setOf(CheckDomain.PERSUASION)))))
        assertEquals("invalid_check_domains", SceneRules.validate(state,
            plan.copy(check = plan.check!!.copy(ability = Ability.STRENGTH))))
        val command: ClientMessage = ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = ReadyCharacters.mira)
        assertEquals(command, GameWireJson.decodeFromString<ClientMessage>(GameWireJson.encodeToString(command)))
    }
}
