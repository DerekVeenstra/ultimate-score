package com.veenstra.ultimatescore

/** Which side of the score a point belongs to. */
enum class Team { US, THEM }

/** One point being scored, at the wall-clock time it happened. */
data class ScoreEvent(val team: Team, val atMillis: Long)

/**
 * The entire state of a game. Scores are *derived* from [history] rather than stored as two
 * counters — that makes undo trivially correct (just drop the last event) and makes the state
 * impossible to desync. See PLAN.md section4 "State model".
 *
 * [usTeam]/[themTeam] carry each side's chosen name and colour, picked when the game is started
 * (see NewGameSetupScreen) and defaulting to the plain "US"/"THEM", no-colour look.
 */
data class GameState(
    val history: List<ScoreEvent> = emptyList(),
    val usTeam: TeamConfig = TeamConfig.DEFAULT_US,
    val themTeam: TeamConfig = TeamConfig.DEFAULT_THEM,
) {
    val us: Int get() = history.count { it.team == Team.US }
    val them: Int get() = history.count { it.team == Team.THEM }
    val canUndo: Boolean get() = history.isNotEmpty()

    fun configFor(team: Team): TeamConfig = when (team) {
        Team.US -> usTeam
        Team.THEM -> themTeam
    }

    fun scoreFor(team: Team): Int = when (team) {
        Team.US -> us
        Team.THEM -> them
    }
}
