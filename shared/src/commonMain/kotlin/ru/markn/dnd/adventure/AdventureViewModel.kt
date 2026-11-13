package ru.markn.dnd.adventure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ru.markn.dnd.adventure.diagnostics.GameLogger

class AdventureViewModel(
    private val engine: AdventureEngine = AdventureEngine(logger = GameLogger.Platform),
) : ViewModel() {
    var state by mutableStateOf(engine.newGame())
        private set

    fun submit(playerId: String, turnId: TurnId, text: String) {
        state = engine.submit(state, playerId, turnId, text)
    }

    fun roll(playerId: String, checkId: CheckId) {
        state = engine.roll(state, playerId, checkId)
    }

    fun cancelCheck(playerId: String, checkId: CheckId) {
        state = engine.cancelCheck(state, playerId, checkId)
    }

    fun endTurn(playerId: String, turnId: TurnId) {
        state = engine.endTurn(state, playerId, turnId)
    }

    fun restart() {
        state = engine.newGame(sessionId = state.sessionId + 1, scenarioId = state.scenarioId)
    }

    fun selectScenario(id: String) {
        state = engine.newGame(sessionId = state.sessionId + 1, scenarioId = id)
    }
}
