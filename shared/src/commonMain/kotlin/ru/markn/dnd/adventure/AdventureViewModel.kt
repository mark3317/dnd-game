package ru.markn.dnd.adventure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ru.markn.dnd.adventure.diagnostics.GameLogger

class AdventureViewModel(
    private val engine: AdventureEngine = AdventureEngine(logger = GameLogger.Platform),
    private val playbackClock: () -> Long = ::storyNowMillis,
) : ViewModel() {
    private var currentState by mutableStateOf(engine.newGame().schedulePlayback(null, playbackClock()))
    var state: AdventureState
        get() = currentState
        private set(value) { currentState = value.schedulePlayback(currentState, playbackClock()) }
    var party by mutableStateOf(ReadyCharacters.all)
        private set

    fun selectParty(profiles: List<CharacterProfile>) {
        require(profiles.size in 2..4 && profiles.all { it.validationError() == null })
        require(profiles.map { it.name.lowercase() }.distinct().size == profiles.size)
        party = profiles.toList()
    }

    private fun newGame(id: String) {
        val scenario = requireNotNull(Scenarios.find(id))
        state = engine.newGame(state.sessionId + 1, party.mapIndexed { index, profile ->
            Player("player-${index + 1}", "Игрок ${index + 1}", profile.hero(scenario), scenario.startLocation)
        }, id)
    }

    fun submit(playerId: String, turnId: TurnId, text: String) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.submit(state, playerId, turnId, text)
    }

    fun combat(action: ru.markn.dnd.combat.DemoAction?, target: ru.markn.dnd.combat.Tile?) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.combatCommand(state, state.activePlayer.id, state.turnId, action, target)
    }

    fun roll(playerId: String, checkId: CheckId) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.roll(state, playerId, checkId)
    }

    fun rollWithTalent(playerId: String, checkId: CheckId) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.roll(state, playerId, checkId, useRacialTalent = true)
    }

    fun cancelCheck(playerId: String, checkId: CheckId) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.cancelCheck(state, playerId, checkId)
    }

    fun endTurn(playerId: String, turnId: TurnId) {
        if (playbackClock() < state.playback.busyUntil) return
        state = engine.endTurn(state, playerId, turnId)
    }

    fun restart() {
        newGame(state.scenarioId)
    }

    fun selectScenario(id: String) {
        newGame(id)
    }
}
