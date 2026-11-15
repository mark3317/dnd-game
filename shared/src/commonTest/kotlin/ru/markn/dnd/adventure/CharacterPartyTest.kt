package ru.markn.dnd.adventure

import kotlin.test.*

class CharacterPartyTest {
    @Test fun localSetupAndRestartKeepSelectedBuildsAcrossBothStories() {
        var now = 1000L
        val model = AdventureViewModel(AdventureEngine(DiceRoller { 1 }), playbackClock = { now })
        val custom = ReadyCharacters.rin.copy(name = "Ника", race = CharacterRace.HALFLING)
        val party = ReadyCharacters.all.take(3) + custom
        model.selectParty(party)
        model.selectScenario(Lighthouse.ID)
        assertEquals(party, model.state.players.map { it.hero.profile })
        val opening = model.state
        model.endTurn(model.state.activePlayer.id, model.state.turnId)
        assertEquals(opening, model.state)
        repeat(3) {
            now = model.state.playback.busyUntil
            model.endTurn(model.state.activePlayer.id, model.state.turnId)
        }
        now = model.state.playback.busyUntil
        model.submit(model.state.activePlayer.id, model.state.turnId, Lighthouse.CLIMB.text)
        now = model.state.playback.busyUntil
        model.rollWithTalent(model.state.activePlayer.id, model.state.pendingCheck!!.id)
        assertTrue(model.state.players.last().hero.racialTalentUsed)
        model.restart()
        assertEquals(party, model.state.players.map { it.hero.profile })
        assertTrue(model.state.players.all { !it.hero.racialTalentUsed && it.hero.health == it.hero.maxHealth })
        model.selectScenario(FloodedArchive.ID)
        assertEquals(party, model.state.players.map { it.hero.profile })
        assertTrue(model.state.players.all { it.location == FloodedArchive.HALL && it.hero.inventory == FloodedArchive.definition.startingItems })
        val previous = model.party
        assertFailsWith<IllegalArgumentException> { model.selectParty(listOf(custom, custom)) }
        assertEquals(previous, model.party)
    }

    @Test fun fourReadyProfilesCanStartWithoutTheCharacterEditor() {
        val model = AdventureViewModel(AdventureEngine(DiceRoller { 1 }))
        assertEquals(4, ReadyCharacters.all.size)
        assertTrue(ReadyCharacters.all.all { it.validationError() == null })
        model.selectParty(ReadyCharacters.all)
        model.selectScenario(ArchiveDemo.ID)
        assertEquals(ReadyCharacters.all, model.state.players.map { it.hero.profile })
        assertEquals(13, model.state.players.single { it.hero.name == "Лада" }.hero.health)
    }
}
