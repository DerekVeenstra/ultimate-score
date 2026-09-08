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
 * @param historyStore where the game is loaded from and saved to. Optional — the default
 *   `null` means no persistence, which keeps this class constructible with no Android framework
 *   dependency in plain JVM unit tests (see ScoreViewModelTest). Production code supplies a real
 *   [DataStoreScoreRepository] (see ScoreScreen.kt's `rememberScoreViewModel`).
 * @param presetStore where the "my teams"/"opponents" preset lists are loaded from and saved to
 *   (PLAN.md section 13). Optional the same way [historyStore] is, and for the same reason — see
 *   ScoreViewModelPresetTest's in-memory fake. `null` means presets start and stay empty, same as
 *   [historyStore] `null` means no persistence.
 * @param clock injectable for tests; defaults to the real wall clock.
 * @param presetIdGenerator generates each new preset's stable [TeamPreset.id]. Defaults to the
 *   wall-clock millisecond it was created — good enough since a person creates at most a handful
 *   of teams by hand — but is a parameter so tests can supply a deterministic sequence instead.
 */
class ScoreViewModel(
    private val historyStore: ScoreHistoryStore? = null,
    private val presetStore: TeamPresetStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val presetIdGenerator: () -> String = { System.currentTimeMillis().toString() },
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

    private val _presets = MutableStateFlow(TeamPresetLists())

    /** The saved "my teams" and "opponents" lists (PLAN.md section 13). Both start empty. */
    val presets: StateFlow<TeamPresetLists> = _presets.asStateFlow()

    init {
        historyStore?.let { store ->
            viewModelScope.launch {
                _state.value = store.load()
                _isReady.value = true
            }
        }
        presetStore?.let { store ->
            viewModelScope.launch {
                _presets.value = store.loadPresets()
            }
        }
    }

    fun score(team: Team) = dispatch(GameAction.Score(team))

    fun undo() = dispatch(GameAction.Undo)

    /** Starts a fresh game with the teams chosen in the setup screen (defaults to US/THEM). */
    fun newGame(
        usTeam: TeamConfig = TeamConfig.DEFAULT_US,
        themTeam: TeamConfig = TeamConfig.DEFAULT_THEM,
    ) = dispatch(GameAction.NewGame(usTeam, themTeam))

    private fun dispatch(action: GameAction) {
        _state.update { reduce(it, action, clock()) }
        historyStore?.let { store ->
            viewModelScope.launch { store.save(_state.value) }
        }
    }

    /**
     * Creates a new preset in [group] and returns it, so the setup screen can select it for the
     * current game in the same pass that created it (PLAN.md section 13's "inline creation"). A
     * blank name (after [sanitizeName] strips control characters and trims whitespace) falls back
     * to "New team" rather than saving an unlabeled row — the setup screen's own flow never
     * actually reaches this with a blank name (it treats a canceled/empty text-input as "don't
     * create"), but the fallback keeps this function safe to call directly, e.g. from a test.
     */
    fun addPreset(group: PresetGroup, name: String, color: TeamColor): TeamPreset {
        val preset = TeamPreset(
            id = presetIdGenerator(),
            name = sanitizeName(name).ifEmpty { "New team" },
            color = color,
        )
        updatePresets(group) { it + preset }
        return preset
    }

    /** No-op if [id] isn't in [group] — e.g. a race with a delete from another recomposition. */
    fun renamePreset(group: PresetGroup, id: String, newName: String) {
        val sanitized = sanitizeName(newName)
        if (sanitized.isEmpty()) return
        updatePresets(group) { list -> list.map { if (it.id == id) it.copy(name = sanitized) else it } }
    }

    fun recolorPreset(group: PresetGroup, id: String, color: TeamColor) {
        updatePresets(group) { list -> list.map { if (it.id == id) it.copy(color = color) else it } }
    }

    /**
     * Only removes the preset from the saved list. If it was the one selected for the current
     * game in the setup screen, falling back to the default US/THEM identity for that side
     * (PLAN.md section 13's selection semantics) is the setup screen's own responsibility, since
     * that selection lives in its Compose state, not here.
     */
    fun deletePreset(group: PresetGroup, id: String) {
        updatePresets(group) { list -> list.filterNot { it.id == id } }
    }

    private fun updatePresets(group: PresetGroup, transform: (List<TeamPreset>) -> List<TeamPreset>) {
        val updated = transform(_presets.value.forGroup(group))
        _presets.update { it.withGroup(group, updated) }
        presetStore?.let { store ->
            viewModelScope.launch { store.savePresets(group, updated) }
        }
    }
}
