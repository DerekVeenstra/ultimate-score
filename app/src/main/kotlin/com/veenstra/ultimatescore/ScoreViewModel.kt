package com.veenstra.ultimatescore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
 * @param savedGameStore where the score-history list of completed games (the "Done" feature) is
 *   loaded from and saved to. Optional/nullable for the same reason [presetStore] is — see
 *   ScoreViewModelSavedGameTest's in-memory fake.
 * @param clock injectable for tests; defaults to the real wall clock.
 * @param presetIdGenerator generates each new preset's stable [TeamPreset.id]. Defaults to the
 *   wall-clock millisecond it was created — good enough since a person creates at most a handful
 *   of teams by hand — but is a parameter so tests can supply a deterministic sequence instead.
 * @param savedGameIdGenerator generates each newly-completed game's stable [SavedGame.id]. Same
 *   defaulting/testing reasoning as [presetIdGenerator]; a separate parameter (rather than sharing
 *   one generator) since the two id spaces are unrelated and tests for one shouldn't have to know
 *   about the other's sequence.
 */
class ScoreViewModel(
    private val historyStore: ScoreHistoryStore? = null,
    private val presetStore: TeamPresetStore? = null,
    private val savedGameStore: SavedGameStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val presetIdGenerator: () -> String = { System.currentTimeMillis().toString() },
    private val savedGameIdGenerator: () -> String = { System.currentTimeMillis().toString() },
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

    private val _savedGames = MutableStateFlow<List<SavedGame>>(emptyList())

    /** Completed games archived via [completeGame] — the new-game screen's "Score history". */
    val savedGames: StateFlow<List<SavedGame>> = _savedGames.asStateFlow()

    // Tracked so updatePresets/updateSavedGames can wait for the initial load before reading
    // _presets/_savedGames — unlike [historyStore], nothing in the UI gates presets/savedGames
    // interaction on a `ready` flag the way ScoreScreen is gated on [isReady], so a mutation
    // (e.g. completing a game, or adding a preset) that lands before the real DataStore read
    // finishes would otherwise read the *default empty* in-memory value, append to that, and
    // persist a shrunk list — silently discarding whatever was already saved on disk. Found by
    // exactly that sequence on the real emulator (see the "Done" feature's test notes) rather
    // than reasoned out in the abstract.
    private val presetsLoadJob: Job? = presetStore?.let { store ->
        viewModelScope.launch { _presets.value = store.loadPresets() }
    }
    private val savedGamesLoadJob: Job? = savedGameStore?.let { store ->
        viewModelScope.launch { _savedGames.value = store.loadSavedGames() }
    }

    init {
        historyStore?.let { store ->
            viewModelScope.launch {
                _state.value = store.load()
                _isReady.value = true
            }
        }
    }

    fun score(team: Team) = dispatch(GameAction.Score(team))

    fun undo() = dispatch(GameAction.Undo)

    /**
     * Starts a fresh game with the teams and ABBA starting gender chosen in the setup screen
     * (all default to the plain US/THEM, no-ABBA game).
     */
    fun newGame(
        usTeam: TeamConfig = TeamConfig.DEFAULT_US,
        themTeam: TeamConfig = TeamConfig.DEFAULT_THEM,
        abbaStart: Gender? = null,
    ) = dispatch(GameAction.NewGame(usTeam, themTeam, abbaStart))

    /**
     * Archives the game in progress to [savedGames] (its final score and both teams' identities,
     * at whatever point it stood — even 0-0, if that's genuinely how it ended), then clears the
     * live game's history so a relaunch after this never shows the just-finished score as still
     * in progress. The teams and ABBA choice are *kept*, not reset to the plain defaults — the
     * setup screen the UI shows next (PLAN.md's "Done" feature) pre-selects a team by matching it
     * against the current game, the same stickiness [newGame] already relies on for a recurring
     * matchup, so this reuses that action rather than a bespoke reducer branch.
     */
    fun completeGame() {
        val current = _state.value
        val saved = SavedGame(
            id = savedGameIdGenerator(),
            usTeam = current.usTeam,
            themTeam = current.themTeam,
            usScore = current.us,
            themScore = current.them,
            completedAtMillis = clock(),
        )
        updateSavedGames { it + saved }
        dispatch(GameAction.NewGame(current.usTeam, current.themTeam, current.abbaStart))
    }

    /** No-op if [id] isn't in [savedGames] — e.g. a race with a delete from another recomposition. */
    fun deleteSavedGame(id: String) {
        updateSavedGames { list -> list.filterNot { it.id == id } }
    }

    /**
     * Waits for [savedGamesLoadJob] before reading [_savedGames] — see that property's doc for
     * why. `join()` returns immediately with no suspension if the load already finished (the
     * overwhelmingly common case — a game takes minutes to play, DataStore's initial read takes
     * milliseconds), so this costs nothing in the normal path.
     */
    private fun updateSavedGames(transform: (List<SavedGame>) -> List<SavedGame>) {
        viewModelScope.launch {
            savedGamesLoadJob?.join()
            val updated = transform(_savedGames.value)
            _savedGames.value = updated
            savedGameStore?.saveSavedGames(updated)
        }
    }

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

    /** Waits for [presetsLoadJob] before reading [_presets] — see that property's doc for why. */
    private fun updatePresets(group: PresetGroup, transform: (List<TeamPreset>) -> List<TeamPreset>) {
        viewModelScope.launch {
            presetsLoadJob?.join()
            val updated = transform(_presets.value.forGroup(group))
            _presets.update { it.withGroup(group, updated) }
            presetStore?.savePresets(group, updated)
        }
    }
}
