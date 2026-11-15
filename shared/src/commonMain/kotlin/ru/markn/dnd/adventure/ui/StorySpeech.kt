package ru.markn.dnd.adventure.ui

import androidx.compose.runtime.*
import io.github.nadeemiqbal.soundscape.core.AudioSource
import io.github.nadeemiqbal.soundscape.core.PlaybackState
import io.github.nadeemiqbal.soundscape.player.MediaItem
import io.github.nadeemiqbal.soundscape.player.SoundscapePlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.*
import ru.markn.dnd.generated.resources.Res

private data class VoiceCue(val key: String, val entry: JournalEntry, val voice: String, val startsAt: Long, val endsAt: Long)
private data class VoiceFile(val url: String, val duration: Long)

class StorySpeech(private val preferences: GamePreferences) {
    val player = runCatching { SoundscapePlayer.create() }.getOrNull()
    val enabled: Boolean get() = preferences.speechEnabled
    val volume: Float get() = preferences.speechVolume
    var status by mutableStateOf("")
    var current by mutableStateOf<TimedSpeech?>(null)
    var caption by mutableStateOf<TimedSpeech?>(null)
    var pending by mutableIntStateOf(0)
    var skipVersion by mutableIntStateOf(0)
    val available: Boolean get() = enabled && player != null
    val busy: Boolean get() = available && pending > 0
    fun toggle() {
        preferences.changeSpeech(!enabled)
        if (!enabled) { skip(); runCatching { player?.pause() } }
    }
    fun loudness(value: Float) { preferences.changeSpeechVolume(value); runCatching { player?.setVolume(volume) } }
    fun skip() { skipVersion++; current = null; caption = null; runCatching { player?.stop() } }
    fun resume() { if (enabled) runCatching { player?.play() } }
}

val LocalStorySpeech = staticCompositionLocalOf<StorySpeech?> { null }

/** Sentence-buffered synthesis: one shared server cache, no model or provider key on clients. */
@Composable
fun rememberStorySpeech(state: AdventureState, now: Long, baseUrl: String): StorySpeech {
    val preferences = LocalGamePreferences.current ?: remember { GamePreferences() }
    val speech = remember(state.sessionId, baseUrl, preferences) { StorySpeech(preferences) }
    val player = speech.player
    var positionMillis by remember(player) { mutableLongStateOf(0) }
    LaunchedEffect(player) { player?.position?.collect { positionMillis = it.inWholeMilliseconds } }
    val client = remember(speech) { SpeechClient(baseUrl) }
    val channel = remember(speech) { Channel<Pair<VoiceCue, Deferred<VoiceFile?>>>(64) }
    val scope = remember(speech) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    // One current synthesis and one lookahead, rather than flooding the single GPU worker.
    val preparationSlots = remember(speech) { Semaphore(2) }
    val seen = remember(speech) { mutableSetOf<String>() }
    val joinedAt = remember(speech) { now }
    val offset = now - storyNowMillis()
    val latestOffset by rememberUpdatedState(offset)
    val latestEnabled by rememberUpdatedState(speech.enabled)
    var prepared by remember { mutableStateOf<List<PreparedSpeech>>(emptyList()) }
    var catalogLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        prepared = runCatching { Json.decodeFromString<List<PreparedSpeech>>(Res.readBytes("files/speech/catalog.json").decodeToString()) }.getOrDefault(emptyList())
        catalogLoaded = true
    }
    DisposableEffect(speech, client) {
        onDispose { scope.cancel(); channel.close(); client.close(); runCatching { player?.close() } }
    }
    LaunchedEffect(state.playback, speech.enabled, catalogLoaded) {
        if (!catalogLoaded || !speech.enabled || player == null) return@LaunchedEffect
        val cues = buildList {
            var cursor = state.playback.openingAt
            state.openingShots().forEachIndexed { index, shot ->
                val end = cursor + subtitleDuration(shot.text)
                add(VoiceCue("opening:$index", JournalEntry(-1L - index, EntryKind.NARRATOR, shot.text), "narrator", cursor, end)); cursor = end
            }
            state.playback.speeches.forEach { timed ->
                add(VoiceCue("entry:${timed.entry.id}:${timed.startsAt}:${timed.entry.text}", timed.entry, state.speechVoice(timed.entry), timed.startsAt, timed.endsAt))
            }
            if (state.playback.endingAt > 0) {
                cursor = state.playback.endingAt
                state.endingShots().forEachIndexed { index, shot ->
                    val end = cursor + subtitleDuration(shot.text)
                    add(VoiceCue("ending:$index", JournalEntry(-1000L - index, EntryKind.NARRATOR, shot.text), "narrator", cursor, end)); cursor = end
                }
            }
        }.sortedBy { it.startsAt }
        for (cue in cues) {
            if (cue.endsAt <= joinedAt || !seen.add(cue.key)) continue
            val asset = prepared.firstOrNull { it.voice == cue.voice && it.text == cue.entry.text }
            val file = scope.async {
                if (asset != null) VoiceFile(Res.getUri("files/speech/${asset.file}"), asset.duration_ms)
                else preparationSlots.withPermit { try {
                    withTimeout(15_000) {
                        var result = client.prepare(SpeechRequest(cue.entry.text, cue.voice))
                        while (result.status in listOf("queued", "generating")) {
                            delay(250); result = client.status(result.id)
                        }
                        if (result.status == "ready") VoiceFile(client.audioUrl(result.id), result.duration_ms) else null
                    }
                } catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled else null }
                catch (_: Exception) { null } }
            }
            speech.pending++
            channel.send(cue to file)
        }
    }
    LaunchedEffect(speech, player) {
        if (player == null) { speech.status = "Аудиоплеер недоступен"; return@LaunchedEffect }
        for ((cue, preparation) in channel) {
            try {
                if (!latestEnabled) { preparation.cancel(); continue }
                val skip = speech.skipVersion
                speech.status = "Подготовка голоса…"
                while (!preparation.isCompleted && latestEnabled && skip == speech.skipVersion) delay(50)
                if (!latestEnabled || skip != speech.skipVersion) { preparation.cancel(); continue }
                val audio = preparation.await()
                if (!latestEnabled || skip != speech.skipVersion) continue
                // Cinematics follow the server clock; dialogue follows actual audio completion.
                if (!cue.key.startsWith("entry:"))
                    delay((cue.startsAt - storyNowMillis() - latestOffset).coerceAtLeast(0))
                if (!latestEnabled || skip != speech.skipVersion) continue
                if (audio == null) {
                    speech.status = "Озвучка недоступна · история продолжается текстом"
                    val start = storyNowMillis() + latestOffset
                    val duration = subtitleDuration(cue.entry.text)
                    speech.current = TimedSpeech(cue.entry, start, start + duration)
                    speech.caption = speech.current
                    val deadline = storyNowMillis() + duration
                    while (latestEnabled && skip == speech.skipVersion && storyNowMillis() < deadline) delay(50)
                    continue
                }
                player.stop()
                positionMillis = 0
                player.setVolume(speech.volume)
                player.setQueue(listOf(MediaItem(cue.key, AudioSource.Url(audio.url))))
                val start = storyNowMillis() + latestOffset
                speech.current = TimedSpeech(cue.entry, start, start + audio.duration)
                speech.caption = speech.current
                speech.status = "Говорит ${state.speakerName(cue.entry)}"
                player.play()
                val deadline = storyNowMillis() + audio.duration + 8000
                var hasPlayed = false
                var hasProgress = false
                while (latestEnabled && speech.skipVersion == skip && storyNowMillis() < deadline) {
                    delay(50)
                    val playback = player.state.value
                    val playbackNow = storyNowMillis() + latestOffset
                    speech.current = speech.current?.copy(startsAt = playbackNow - positionMillis,
                        endsAt = playbackNow + (audio.duration - positionMillis).coerceAtLeast(0))
                    speech.caption = speech.current
                    if (playback == PlaybackState.Playing) hasPlayed = true
                    if (hasPlayed && positionMillis in 1 until audio.duration - 50) hasProgress = true
                    if (playback is PlaybackState.Error) speech.status = "Разрешите звук в настройках кнопкой «Включить звук»"
                    if (hasPlayed && (playback == PlaybackState.Ended || (hasProgress && positionMillis >= audio.duration - 50))) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { speech.status = "Не удалось воспроизвести реплику" }
            finally { speech.pending--; speech.current = null }
        }
    }
    return speech
}
