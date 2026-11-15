package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.russhwolf.settings.Settings
import io.github.nadeemiqbal.soundscape.core.AudioSource
import io.github.nadeemiqbal.soundscape.core.PlaybackState
import io.github.nadeemiqbal.soundscape.player.MediaItem
import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.markn.dnd.generated.resources.Res

enum class MusicCue(val file: String) {
    MENU("fantasy_theme"), OPENING("contemplation"), EXPLORATION("contemplation"),
    COMBAT("battle"), VICTORY("fantasy_theme"), DEFEAT("contemplation")
}

class StoryMusic {
    private val settings = runCatching { Settings() }.getOrNull()
    val player = runCatching { SoundscapePlayer.create() }.getOrNull()
    var cue by mutableStateOf(MusicCue.MENU)
    var enabled by mutableStateOf(runCatching { settings?.getBoolean("music.enabled", true) ?: true }.getOrDefault(true))
        private set
    var volume by mutableFloatStateOf(runCatching { settings?.getFloat("music.volume", .30f) ?: .30f }.getOrDefault(.30f).coerceIn(0f, 1f))
        private set
    var active by mutableStateOf(true)
    var loadedFile: String? = null

    fun toggle() {
        enabled = !enabled
        runCatching { settings?.putBoolean("music.enabled", enabled) }
        if (enabled) resume() else runCatching { player?.pause() }
    }
    fun setLoudness(value: Float) {
        volume = value.coerceIn(0f, 1f)
        runCatching { settings?.putFloat("music.volume", volume) }
        runCatching { player?.setVolume(volume) }
    }
    // Called directly in click handlers so browsers can grant audio playback permission.
    fun resume(force: Boolean = false) {
        if (enabled && active) runCatching {
            player?.setVolume(volume)
            if (force || player?.state?.value != PlaybackState.Playing) player?.play()
        }
    }
}

val LocalStoryMusic = staticCompositionLocalOf<StoryMusic?> { null }

@Composable
fun StoryMusicHost(music: StoryMusic) {
    val player = music.player ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { music.active = false; runCatching { player.pause() } }
                Lifecycle.Event.ON_START -> { music.active = true; music.resume() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runCatching { player.close() } }
    }
    LaunchedEffect(music.cue.file, music.enabled, music.active) {
        if (!music.enabled || !music.active) { runCatching { player.pause() }; return@LaunchedEffect }
        if (music.loadedFile != music.cue.file) {
            val initial = player.volume.value
            if (music.loadedFile != null) repeat(12) { step ->
                runCatching { player.setVolume(initial * (1f - (step + 1) / 12f)) }; delay(40)
            }
            runCatching {
                player.stop()
                player.setQueue(listOf(MediaItem(music.cue.file, AudioSource.Url(Res.getUri("files/music/${music.cue.file}.mp3")))))
                music.loadedFile = music.cue.file
                player.setVolume(0f)
                player.play()
            }
            repeat(18) { step ->
                runCatching { player.setVolume(music.volume * (step + 1) / 18f) }; delay(40)
            }
        } else music.resume()
    }
    LaunchedEffect(player) {
        // The web backend does not publish Ended. Its position stays at the file duration.
        combine(player.position, player.duration, player.state) { position, duration, state ->
            state == PlaybackState.Ended || (state == PlaybackState.Playing && duration != null && duration.inWholeMilliseconds > 0 &&
                position.inWholeMilliseconds >= duration.inWholeMilliseconds - 20)
        }.distinctUntilChanged().collect { ended ->
            if (ended && music.enabled && music.active) runCatching { player.stop(); player.play() }
        }
    }
}
