package ru.markn.dnd.adventure

import com.russhwolf.settings.MapSettings
import ru.markn.dnd.adventure.ui.GamePreferences
import kotlin.test.*

class GamePreferencesTest {
    @Test fun reducedMotionDefaultsOffAndPersistsIndependentlyOfCameraAudioAndText() {
        val storage = MapSettings().apply {
            putBoolean("ui.motion", false)
            putFloat("speech.volume", .4f)
            putFloat("ui.subtitleScale", 1.2f)
        }
        val preferences = GamePreferences(storage)
        assertFalse(preferences.reduceMotion, "Existing camera preferences must not silently enable a new accessibility mode")
        preferences.changeMotion(true)
        preferences.changeReduceMotion(true)
        val reopened = GamePreferences(storage)
        assertTrue(reopened.motion)
        assertTrue(reopened.reduceMotion)
        assertEquals(.4f, reopened.speechVolume)
        assertEquals(1.2f, reopened.subtitleScale)
        reopened.changeReduceMotion(false)
        assertTrue(GamePreferences(storage).motion)
        assertFalse(GamePreferences(storage).reduceMotion)
    }

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
        preferences.changeEffects(false)
        preferences.changeEffectsVolume(.35f)
        val reopened = GamePreferences(storage)
        assertTrue(reopened.speechEnabled)
        assertEquals(.65f, reopened.speechVolume)
        assertEquals(1.2f, reopened.subtitleScale)
        assertFalse(reopened.motion)
        assertFalse(reopened.effectsEnabled)
        assertEquals(.35f, reopened.effectsVolume)
    }

    @Test fun invalidStoredValuesCannotBreakAudioOrSubtitleLayout() {
        val storage = MapSettings().apply {
            putFloat("speech.volume", Float.NaN)
            putFloat("ui.subtitleScale", Float.POSITIVE_INFINITY)
            putFloat("effects.volume", Float.NaN)
        }
        val preferences = GamePreferences(storage)
        assertEquals(.9f, preferences.speechVolume)
        assertEquals(1f, preferences.subtitleScale)
        assertEquals(.65f, preferences.effectsVolume)
        preferences.changeSpeechVolume(2f)
        preferences.changeSubtitleScale(.1f)
        assertEquals(1f, preferences.speechVolume)
        assertEquals(.8f, preferences.subtitleScale)
        preferences.changeEffectsVolume(5f)
        assertEquals(1f, preferences.effectsVolume)
        preferences.changeEffectsVolume(Float.NEGATIVE_INFINITY)
        assertEquals(1f, preferences.effectsVolume)
        preferences.changeSpeechVolume(Float.NaN)
        preferences.changeSubtitleScale(Float.NaN)
        assertEquals(1f, preferences.speechVolume)
        assertEquals(.8f, preferences.subtitleScale)
    }
}
