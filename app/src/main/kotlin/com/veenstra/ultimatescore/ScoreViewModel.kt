package com.veenstra.ultimatescore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the current [GameState] and exposes the three actions the UI can take. All state
 * changes go through [reduce], so the logic worth testing lives in GameReducerTest, not here.
 *
 * @param historyStore where the event log is loaded from and saved to. Optional — the default
 *   `null` means no persistence, which keeps this class constructible with no Android framework
 *   dependency in plain JVM unit tests (see ScoreViewModelTest). Production code supplies a real
 *   [DataStoreScoreRepository] (see ScoreScreen.kt's `rememberScoreViewModel`).
 * @param clock injectable for tests; defaults to the real wall clock.
 */
class ScoreViewModel(
    private val historyStore: ScoreHistoryStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _isReady = MutableStateFlow(historyStore == null)

    /**
     * False until a persisted history has finished loading. The UI should render nothing (not
     * an 0-0 placeholder) while this is false, so a game in progress on disk is never briefly
     * shown as reset. See PLAN.md section 4 "Persistence".
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        historyStore?.let { store ->
            viewModelScope.launch {
                _state.value = GameState(store.loadHistory())
                _isReady.value = true
            }
        }
    }

    fun score(team: Team) = dispatch(GameAction.Score(team))

    fun undo() = dispatch(GameAction.Undo)

    fun newGame() = dispatch(GameAction.NewGame)

    private fun dispatch(action: GameAction) {
        _state.update { reduce(it, action, clock()) }
        historyStore?.let { store ->
            viewModelScope.launch { store.saveHistory(_state.value.history) }
        }
    }
}
