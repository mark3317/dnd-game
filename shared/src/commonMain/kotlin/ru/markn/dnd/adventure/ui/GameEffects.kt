package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nadeemiqbal.soundscape.core.AudioSource
import io.github.nadeemiqbal.soundscape.core.PlaybackState
import io.github.nadeemiqbal.soundscape.player.MediaItem
import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.storyNowMillis
import ru.markn.dnd.generated.resources.Res
import kotlin.math.abs

internal val LocalGameEffects = staticCompositionLocalOf<GameEffects?> { null }
internal val LocalSoundPresentationId = staticCompositionLocalOf<String?> { null }

private class EffectSlot(val player: SoundscapePlayer) {
    var asset: GameSoundAsset? = null
    var position = 0L
    var startedAt = 0L
    var played = false
    var progressed = false
    var volume = -1f
}

/** Two one-shot voices plus one quiet environmental bed; no queued stale effects. */
internal class GameEffects(private val preferences: GamePreferences, history: Set<String>) {
    private val gate = GameSoundGate(history)
    private val slots = List(2) { runCatching { SoundscapePlayer.create() }.getOrNull() }
        .filterNotNull().map(::EffectSlot)
    private val ambient = runCatching { SoundscapePlayer.create() }.getOrNull()?.let(::EffectSlot)
    internal var assets: Map<String, GameSoundAsset> = emptyMap()
    var foreground by mutableStateOf(true)
    var connected by mutableStateOf(true)
    var ambienceAllowed by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var status by mutableStateOf("")
        private set
    private var blocked = false
    private var transportPausedAt: Long? = null
    private var serial = 0L
    private var ambientVolume = 0f
    private var knownHistory = history
    private var suppressNextHistory = false
    private val enabled get() = preferences.effectsEnabled && preferences.effectsVolume > 0 && foreground && connected

    init { if (slots.isEmpty()) status = "Аудиоплеер эффектов недоступен" }

    fun play(eventId: String, phase: String, sound: GameSound) {
        if (transportPausedAt != null) return
        if (!gate.claim(eventId, phase, enabled && !blocked)) return
        val asset = assets[sound.assetId] ?: return
        val slot = slots.firstOrNull { it.asset == null } ?: return // Drop overload, never cut another clip's tail.
        start(slot, asset, preferences.effectsVolume * if (speaking) .6f else 1f)
    }

    private fun start(slot: EffectSlot, asset: GameSoundAsset, volume: Float) {
        try {
            require(asset.file.matches(Regex("[a-z0-9_]+\\.wav")))
            slot.asset = asset
            slot.position = 0
            slot.played = false
            slot.progressed = false
            slot.startedAt = storyNowMillis()
            slot.volume = volume
            slot.player.setQueue(listOf(MediaItem("effect:${++serial}", AudioSource.Url(Res.getUri("files/effects/${asset.file}")))))
            slot.player.setVolume(volume)
            slot.player.play()
        } catch (_: Exception) { fail(slot) }
    }

    private fun stop(slot: EffectSlot) {
        runCatching { slot.player.stop() }
        slot.asset = null
        slot.position = 0
    }

    private fun fail(slot: EffectSlot) {
        stop(slot)
        blocked = true
        status = "Эффекты недоступны · попробуйте «Включить звук»"
    }

    private fun ended(slot: EffectSlot): Boolean {
        val duration = slot.asset?.duration_ms ?: return false
        val state = slot.player.state.value
        if (state == PlaybackState.Playing) slot.played = true
        if (slot.played && slot.position in 1 until duration) slot.progressed = true
        return slot.played && (state == PlaybackState.Ended || (slot.progressed && slot.position >= duration))
    }

    internal fun transportPause(paused: Boolean) {
        if (paused == (transportPausedAt != null)) return
        val active = (slots + listOfNotNull(ambient)).filter { it.asset != null }
        if (paused) {
            transportPausedAt = storyNowMillis()
            active.forEach { runCatching { it.player.pause() } }
        } else {
            val delta = storyNowMillis() - checkNotNull(transportPausedAt)
            transportPausedAt = null
            active.forEach { slot ->
                slot.startedAt += delta.coerceAtLeast(0)
                if (enabled && !blocked) runCatching { slot.player.play() }.onFailure { fail(slot) }
            }
        }
    }

    internal fun tick() {
        if (transportPausedAt != null) return
        val now = storyNowMillis()
        if (!enabled) gate.suppress(knownHistory)
        for (slot in slots) {
            val asset = slot.asset ?: continue
            if (!enabled) { stop(slot); continue }
            if (slot.player.state.value is PlaybackState.Error || now - slot.startedAt > asset.duration_ms + 5000) {
                fail(slot); continue
            }
            if (ended(slot)) { stop(slot); continue }
            val target = preferences.effectsVolume * if (speaking) .6f else 1f
            if (abs(slot.volume - target) > .005f) {
                slot.volume = target
                runCatching { slot.player.setVolume(target) }
            }
        }
        val bed = ambient ?: return
        if (!enabled || blocked) {
            if (bed.asset != null) stop(bed)
            ambientVolume = 0f
            return
        }
        val target = if (ambienceAllowed) preferences.effectsVolume * .24f * (if (speaking) .35f else 1f) else 0f
        ambientVolume += (target - ambientVolume) * .22f
        if (bed.asset == null && ambienceAllowed) assets[GameSound.CELLAR.assetId]?.let { start(bed, it, 0f) }
        if (bed.asset != null) {
            if (bed.player.state.value is PlaybackState.Error || (!bed.played && now - bed.startedAt > 5000)) {
                fail(bed); return
            }
            runCatching { bed.player.setVolume(ambientVolume) }
            if (!ambienceAllowed && ambientVolume < .001f) { stop(bed); return }
            if (ended(bed)) {
                stop(bed)
                // A crossfaded asset is used, but gapless looping is not promised on every backend.
                if (ambienceAllowed) assets[GameSound.CELLAR.assetId]?.let { start(bed, it, ambientVolume) }
            }
        }
    }

    /** Direct user gesture, not a coroutine; one-shots from history are never resumed. */
    fun resume() {
        blocked = false
        status = ""
        if (enabled && ambienceAllowed && ambient?.asset == null)
            assets[GameSound.CELLAR.assetId]?.let { start(ambient ?: return, it, ambientVolume) }
    }

    internal fun suspendOutput() {
        gate.suppress(knownHistory)
        slots.filter { it.asset != null }.forEach(::stop)
        ambient?.takeIf { it.asset != null }?.let(::stop)
        ambientVolume = 0f
    }

    internal fun updateHistory(history: Set<String>) {
        knownHistory = history
        if ((!enabled && transportPausedAt == null) || suppressNextHistory) gate.suppress(history)
        suppressNextHistory = false
    }

    internal fun returnToForeground() {
        foreground = true
        // The next composition may contain events received while the app was suspended.
        suppressNextHistory = true
    }

    internal fun observePositions(): List<Pair<SoundscapePlayer, (Long) -> Unit>> = (slots + listOfNotNull(ambient))
        .map { slot -> slot.player to { value: Long -> slot.position = value } }

    internal fun close() { (slots + listOfNotNull(ambient)).forEach { runCatching { it.player.close() } } }
}

@Composable
internal fun rememberGameEffects(sessionId: Long, generation: Long, initialHistory: Set<String>): GameEffects {
    val preferences = LocalGamePreferences.current ?: remember { GamePreferences() }
    return remember(sessionId, generation, preferences) { GameEffects(preferences, initialHistory) }
}

@Composable
internal fun GameEffectsHost(effects: GameEffects, connected: Boolean = true, ambience: Boolean = false, speaking: Boolean = false,
    history: Set<String> = emptySet(), paused: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val foreground = effects.foreground
    SideEffect {
        effects.connected = connected; effects.ambienceAllowed = ambience; effects.speaking = speaking
        effects.transportPause(paused || !foreground)
        effects.updateHistory(history)
        if (!paused && (!connected || !foreground)) effects.suspendOutput()
    }
    DisposableEffect(effects, lifecycle) {
        effects.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { effects.foreground = false; effects.transportPause(true) }
            if (event == Lifecycle.Event.ON_START && !effects.foreground) effects.returnToForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); effects.close() }
    }
    LaunchedEffect(effects) {
        effects.assets = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<List<GameSoundAsset>>(
                Res.readBytes("files/effects/catalog.json").decodeToString()).associateBy { it.id }
        }.getOrDefault(emptyMap())
    }
    for ((player, update) in remember(effects) { effects.observePositions() }) {
        LaunchedEffect(player) { player.position.collect { update(it.inWholeMilliseconds) } }
    }
    LaunchedEffect(effects) { while (isActive) { effects.tick(); delay(50) } }
}
