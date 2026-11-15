package ru.markn.dnd.adventure

import com.russhwolf.settings.MapSettings
import kotlin.test.*

class CharacterLibraryTest {
    @Test fun avatarEditsPersistAndCannotOverwriteAnotherHero() {
        val settings = MapSettings()
        val library = CharacterLibrary(settings)
        val original = ReadyCharacters.bran.copy(name = "Свой герой")
        assertTrue(library.save(original))
        val edited = original.copy(avatarId = PreparedAvatars.id(CharacterRace.ELF, CharacterClass.BARD))
        assertTrue(library.update(original, edited))
        assertEquals(listOf(edited), CharacterLibrary(settings).characters)
        assertFalse(library.update(original, edited.copy(name = "Другой")))
        assertFalse(library.update(edited, edited.copy(name = ReadyCharacters.rin.name)))
        assertFalse(library.update(edited, edited.copy(avatarId = "unknown")))
        assertEquals(listOf(edited), CharacterLibrary(settings).characters)
    }
    @Test fun customProfilesSurviveReopeningAndBothStoriesLeaveTheSavedBuildUnchanged() {
        val settings = MapSettings()
        val library = CharacterLibrary(settings)
        val profile = ReadyCharacters.rin.copy(name = "Ника", description = "Собственная история")
        assertTrue(library.save(profile))
        val reopened = CharacterLibrary(settings)
        assertEquals(listOf(profile), reopened.characters)
        for (scenario in Scenarios.all) {
            val hero = reopened.characters.single().hero(scenario)
            val injured = hero.copy(health = 1, racialTalentUsed = true, inventory = emptyList())
            assertEquals(profile, injured.profile)
            assertEquals(profile, reopened.characters.single())
        }
        assertFalse(reopened.save(profile.copy(name = "ника")))
        assertFalse(reopened.save(ReadyCharacters.rin))
        assertTrue(reopened.remove(profile))
        assertTrue(CharacterLibrary(settings).characters.isEmpty())
    }

    @Test fun invalidOrUnavailableStorageIsNotSilentlyOverwritten() {
        val settings = MapSettings("dnd.characters.v1" to "broken")
        val library = CharacterLibrary(settings)
        assertNotNull(library.error)
        assertFalse(library.save(ReadyCharacters.rin.copy(name = "Ника")))
        assertEquals("broken", settings.getStringOrNull("dnd.characters.v1"))
        assertFalse(CharacterLibrary(null).save(ReadyCharacters.mira.copy(name = "Ника")))
    }

    @Test fun fullLibraryIsBoundedAndFitsTheJvmPreferencesValueLimit() {
        val settings = MapSettings()
        val library = CharacterLibrary(settings)
        repeat(CharacterLibrary.MAX_CHARACTERS) { index ->
            assertTrue(library.save(ReadyCharacters.mira.copy(name = "Герой $index", description = "я".repeat(240))))
        }
        assertFalse(library.save(ReadyCharacters.bran.copy(name = "Лишний")))
        assertTrue(settings.getStringOrNull("dnd.characters.v1")!!.length < 8192)
        assertEquals(library.characters, CharacterLibrary(settings).characters)
    }
}
