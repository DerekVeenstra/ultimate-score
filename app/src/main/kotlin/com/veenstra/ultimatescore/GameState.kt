package com.veenstra.ultimatescore

/** Which side of the score a point belongs to. Labels are fixed for v1 (see PLAN.md section10). */
enum class Team { US, THEM }

/** One point being scored, at the wall-clock time it happened. */
data class ScoreEvent(val team: Team, val atMillis: Long)

/**
 * The entire state of a game. Scores are *derived* from [history] rather than stored as two
 * counters — that makes undo trivially correct (just drop the last event) and makes the state
 * impossible to desync. See PLAN.md section4 "State model".
 */
data class GameState(
    val history: List<ScoreEvent> = emptyList(),
) {
    val us: Int get() = history.count { it.team == Team.US }
    val them: Int get() = history.count { it.team == Team.THEM }
    val canUndo: Boolean get() = history.isNotEmpty()
}
