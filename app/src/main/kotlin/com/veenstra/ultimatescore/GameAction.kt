package com.veenstra.ultimatescore

/** Every way [GameState] can change. */
sealed interface GameAction {
    data class Score(val team: Team) : GameAction
    data object Undo : GameAction
    data object NewGame : GameAction
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
        is GameAction.NewGame -> GameState()
    }
