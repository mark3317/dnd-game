package ru.markn.dnd.adventure

import kotlinx.serialization.json.Json
import kotlin.test.*
import ru.markn.dnd.network.*

class PreparedAvatarsTest {
    @Test fun everyRaceAndClassHasAnAvatarWithoutChangingCharacterRules() {
        assertEquals(16, PreparedAvatars.ids.size)
        for (race in CharacterRace.entries) for (characterClass in CharacterClass.entries) {
            val base = ReadyCharacters.bran.copy(name = "Свой герой", race = race, characterClass = characterClass)
            val profile = base.copy(avatarId = PreparedAvatars.id(race, characterClass))
            assertNull(profile.validationError())
            val hero = profile.hero(ArchiveDemo.definition)
            assertEquals(base.hero(ArchiveDemo.definition).health, hero.health)
            assertEquals(profile, hero.profile)
            val command: ClientMessage = ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = profile)
            assertEquals(command, GameWireJson.decodeFromString<ClientMessage>(GameWireJson.encodeToString(command)))
        }
    }

    @Test fun oldProfilesRemainReadableAndArbitraryAvatarPathsAreRejected() {
        val legacy = Json.encodeToString(ReadyCharacters.rin)
        assertFalse(legacy.contains("avatarId"))
        assertNull(Json.decodeFromString<CharacterProfile>(legacy).avatarId)
        assertNotNull(ReadyCharacters.rin.copy(avatarId = "https://example.org/portrait.png").validationError())
        assertNotNull(ReadyCharacters.rin.copy(avatarId = "../portrait").validationError())
    }
}
