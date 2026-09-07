package com.veenstra.ultimatescore

/** Every way [GameState] can change. */
sealed interface GameAction {
    data class Score(val team: Team) : GameAction
    data object Undo : GameAction

    /**
     * Starts a fresh game, adopting the team names/colours chosen in the setup screen. Both
     * default to the plain "US"/"THEM", no-colour identities, so `NewGame()` on its own is
     * still "reset everything to how the app looked before team colours existed".
     */
    data class NewGame(
        val usTeam: TeamConfig = TeamConfig.DEFAULT_US,
        val themTeam: TeamConfig = TeamConfig.DEFAULT_THEM,
    ) : GameAction
}

/**
 * Pure state transition — no Android dependencies, so this is exercised directly by JVM unit
 * tests (see GameReducerTest). [now] is passed in rather than read from the clock here so the
 * tests are deterministic.
 */
fun reduce(state: GameState, action: GameAction, now: Long): GameState =
    when (action) {
        is GameAction.Score -> state.copy(history = state.history + ScoreEvent(action.team, now))
        is GameAction.Undo -> if (state.canUndo) {
            state.copy(history = state.history.dropLast(1))
        } else {
            state
        }
        is GameAction.NewGame -> GameState(usTeam = action.usTeam, themTeam = action.themTeam)
    }
