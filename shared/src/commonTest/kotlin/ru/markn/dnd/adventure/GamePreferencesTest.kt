package ru.markn.dnd.adventure

import com.russhwolf.settings.MapSettings
import ru.markn.dnd.adventure.ui.GamePreferences
import kotlin.test.*

class GamePreferencesTest {
    @Test fun existingSpeechSettingsAreUsedAndChangesSurviveRestart() {
        val storage = MapSettings().apply {
            putBoolean("speech.enabled", false)
            putFloat("speech.volume", .45f)
        }
        val preferences = GamePreferences(storage)
        assertFalse(preferences.speechEnabled)
        assertEquals(.45f, preferences.speechVolume)
        preferences.changeSpeech(true)
        preferences.changeSpeechVolume(.65f)
        preferences.changeSubtitleScale(1.2f)
        preferences.changeMotion(false)
        val reopened = GamePreferences(storage)
        assertTrue(reopened.speechEnabled)
        assertEquals(.65f, reopened.speechVolume)
        assertEquals(1.2f, reopened.subtitleScale)
        assertFalse(reopened.motion)
    }

    @Test fun invalidStoredValuesCannotBreakAudioOrSubtitleLayout() {
        val storage = MapSettings().apply {
            putFloat("speech.volume", Float.NaN)
            putFloat("ui.subtitleScale", Float.POSITIVE_INFINITY)
        }
        val preferences = GamePreferences(storage)
        assertEquals(.9f, preferences.speechVolume)
        assertEquals(1f, preferences.subtitleScale)
        preferences.changeSpeechVolume(2f)
        preferences.changeSubtitleScale(.1f)
        assertEquals(1f, preferences.speechVolume)
        assertEquals(.8f, preferences.subtitleScale)
        preferences.changeSpeechVolume(Float.NaN)
        preferences.changeSubtitleScale(Float.NaN)
        assertEquals(1f, preferences.speechVolume)
        assertEquals(.8f, preferences.subtitleScale)
    }
}
