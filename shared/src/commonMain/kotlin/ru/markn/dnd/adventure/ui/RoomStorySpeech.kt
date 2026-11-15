package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.*
import io.github.nadeemiqbal.soundscape.core.AudioSource
import io.github.nadeemiqbal.soundscape.core.PlaybackState
import io.github.nadeemiqbal.soundscape.player.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import ru.markn.dnd.adventure.*
import ru.markn.dnd.generated.resources.Res
import ru.markn.dnd.network.*

/** An EOF observed immediately before pause must be consumed, never restarted by play(). */
internal fun roomAudioFinished(hasPlayed: Boolean, hasProgress: Boolean, position: Long,
    duration: Long, ended: Boolean): Boolean = hasPlayed &&
    (ended || (hasProgress && duration > 0 && position >= duration))

/** The server synthesizes once; this host only assigns published sources and plays them. */
@Composable
internal fun rememberRoomStorySpeech(
    state: AdventureState, presentation: RoomPresentation?, now: Long, baseUrl: String,
    connectionGeneration: Long, connected: Boolean, onAck: (RoomSpeechAcknowledgement) -> Unit,
    endingReady: Boolean = true, paused: Boolean = false, pausedAtMillis: Long? = null,
): StorySpeech {
    val preferences = LocalGamePreferences.current ?: remember { GamePreferences() }
    val speech = remember(state.sessionId, baseUrl, preferences, connectionGeneration) { StorySpeech(preferences, roomControlled = true) }
    val reducer = remember(speech) { RoomSpeechPlayback() }
    val player = speech.player
    var position by remember(speech) { mutableLongStateOf(0) }
    val latestPresentation by rememberUpdatedState(presentation)
    val latestConnected by rememberUpdatedState(connected)
    val latestNow by rememberUpdatedState(now)
    val latestState by rememberUpdatedState(state)
    val latestPaused by rememberUpdatedState(paused)
    val latestEndingReady by rememberUpdatedState(endingReady)
    LaunchedEffect(speech) { player?.position?.collect { position = it.inWholeMilliseconds } }
    LaunchedEffect(speech, speech.volume) { runCatching { player?.setVolume(speech.volume) } }
    DisposableEffect(speech) { onDispose { speech.resumeAction = null; runCatching { player?.close() } } }
    LaunchedEffect(speech) {
        // Capture the callback of this connection. An old player cannot ACK via a new socket.
        val ackForThisConnection = onAck
        val sources = mutableMapOf<String, String>()
        var batch: String? = null
        var started: RoomSpeechEventKey? = null
        var hasPlayed = false
        var hasProgress = false
        var pausedSince: TimeMark? = null
        fun perform(effects: List<RoomSpeechEffect>) {
            for (effect in effects) when (effect) {
                is RoomSpeechEffect.Ack -> if (latestConnected) ackForThisConnection(effect.value)
                RoomSpeechEffect.Stop -> {
                    runCatching { player?.stop() }
                    started = null
                }
                is RoomSpeechEffect.Prepare -> {
                    val cue = effect.cue
                    val id = cue.audioId.orEmpty()
                    val url = when {
                        cue.source == RoomSpeechSource.PREPARED && id.matches(Regex("[a-f0-9]{64}\\.wav")) ->
                            runCatching { Res.getUri("files/speech/$id") }.getOrNull()
                        cue.source == RoomSpeechSource.GENERATED && id.matches(Regex("[a-f0-9]{64}")) ->
                            "${baseUrl.trimEnd('/')}/speech/audio/$id.wav"
                        else -> null
                    }
                    if (url != null) {
                        sources[cue.id] = url
                        perform(reducer.prepared(effect.key))
                    } else perform(reducer.failed(effect.key))
                }
                is RoomSpeechEffect.Play -> {
                    val cue = effect.cue
                    try {
                        val url = checkNotNull(sources[cue.id])
                        val target = checkNotNull(player)
                        position = 0
                        hasPlayed = false
                        hasProgress = false
                        started = effect.key
                        target.setQueue(listOf(MediaItem(cue.id, AudioSource.Url(url))))
                        target.setVolume(speech.volume)
                        target.startRoomAudio(effect.offsetMillis)
                    } catch (_: Exception) { perform(reducer.failed(effect.key)) }
                }
            }
        }
        // Called directly by the settings onClick. Never replay a completed/text-only cue.
        speech.resumeAction = {
            val cue = reducer.activeCue
            if (latestConnected && !latestPaused && cue != null && reducer.activeMode == RoomSpeechMode.AUDIO &&
                cue.id == started?.cueId && cue.deadlineAt?.let { latestNow < it } != false) {
                runCatching { player?.startRoomAudio(position.coerceAtLeast(0)) }
            } else speech.status = "Звук будет доступен со следующей реплики"
        }
        while (isActive) {
            if (latestPaused) {
                if (pausedSince == null) {
                    pausedSince = TimeSource.Monotonic.markNow()
                    runCatching { player?.pause() }
                }
                snapshotFlow { latestPaused }.first { !it }
                continue
            }
            pausedSince?.let { since ->
                reducer.resumeAfterPause(since.elapsedNow().inWholeMilliseconds)
                pausedSince = null
                val current = reducer.activeCue
                if (current != null && reducer.activeMode == RoomSpeechMode.AUDIO &&
                    current.id == started?.cueId && player?.currentItem?.value?.id == current.id) {
                    if (roomAudioFinished(hasPlayed, hasProgress, position, current.durationMillis,
                            player.state.value == PlaybackState.Ended)) {
                        perform(reducer.completed(checkNotNull(started)))
                        started = null
                    } else try { player.play() } catch (_: Exception) { perform(reducer.failed(checkNotNull(started))) }
                }
            }
            val value = latestPresentation?.takeIf { it.sessionId == latestState.sessionId }
            if (batch != value?.batchId) { sources.clear(); batch = value?.batchId }
            perform(reducer.update(value, connectionGeneration, latestConnected, latestNow,
                speech.enabled && speech.volume > 0f, player != null, roomAudioRecoverySeekSupported, latestEndingReady))
            val active = reducer.activeCue
            if (active != null && reducer.activeMode == RoomSpeechMode.AUDIO && active.id == started?.cueId &&
                player?.currentItem?.value?.id == active.id) {
                val playback = player.state.value
                if (playback == PlaybackState.Playing) hasPlayed = true
                if (hasPlayed && position in 1 until active.durationMillis) hasProgress = true
                if (playback is PlaybackState.Error) {
                    speech.status = "Озвучка недоступна · история продолжается с субтитрами"
                    perform(reducer.failed(checkNotNull(started)))
                } else if (roomAudioFinished(hasPlayed, hasProgress, position, active.durationMillis, playback == PlaybackState.Ended)) {
                    perform(reducer.completed(checkNotNull(started)))
                    started = null
                }
            }
            val current = reducer.activeCue
            speech.roomCue = reducer.displayedCue ?: value?.currentCue(latestNow)
            speech.current = current?.let { cue ->
                if (reducer.activeMode == RoomSpeechMode.AUDIO) {
                    val elapsed = if (hasPlayed) position.coerceIn(0, cue.durationMillis) else 0L
                    TimedSpeech(cue.entry, latestNow - elapsed, latestNow + (cue.durationMillis - elapsed))
                } else {
                    val start = reducer.activeStartedAt
                    TimedSpeech(cue.entry, start, reducer.activeReadUntil ?: start + roomSpeechTextDuration(cue.entry.text))
                }
            }
            speech.current?.let { speech.caption = it }
            speech.pending = if (value?.busy == true && latestConnected) 1 else 0
            if (!latestConnected) {
                speech.current = null
                speech.caption = null
                speech.roomCue = null
                speech.status = "Связь прервана · подключитесь снова"
            } else if (current != null && reducer.activeMode == RoomSpeechMode.AUDIO) {
                speech.status = "Говорит ${latestState.speakerName(current.entry)}"
            } else if (current != null) {
                speech.status = if (!speech.enabled || speech.volume == 0f) "Субтитры · озвучка выключена"
                    else "История продолжается с субтитрами"
            } else if (value?.phase == RoomPresentationPhase.PREPARING && speech.current == null) {
                speech.status = "Подготовка реплики…"
            } else if (value?.busy == false) {
                speech.status = ""
            }
            delay(25)
        }
    }
    return speech
}
