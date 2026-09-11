package com.veenstra.ultimatescore

/** Which side of the score a point belongs to. */
enum class Team { US, THEM }

/**
 * The majority gender of the line for a point, in mixed play. `M` = more men-matching players,
 * `F` = more women-matching players. One of these is chosen for the first point in setup; the
 * ABBA rule (see [genderForPoint]) derives every later point from it.
 */
enum class Gender {
    M, F;

    fun opposite(): Gender = if (this == M) F else M
}

/**
 * The majority gender for a given 1-indexed [point] under the mixed-Ultimate "ABBA" rule: the
 * chosen ratio ([start]) is used for point 1, then flipped in pairs — A B B A A B B A … So
 * points 1, 4, 5, 8, 9 … use [start] and points 2, 3, 6, 7, 10 … use its opposite.
 */
fun genderForPoint(point: Int, start: Gender): Gender =
    if (point % 4 == 1 || point % 4 == 0) start else start.opposite()

/** One point being scored, at the wall-clock time it happened. */
data class ScoreEvent(val team: Team, val atMillis: Long)

/**
 * The entire state of a game. Scores are *derived* from [history] rather than stored as two
 * counters — that makes undo trivially correct (just drop the last event) and makes the state
 * impossible to desync. See PLAN.md section4 "State model".
 *
 * [usTeam]/[themTeam] carry each side's chosen name and colour, picked when the game is started
 * (see NewGameSetupScreen) and defaulting to the plain "US"/"THEM", no-colour look.
 *
 * [abbaStart] is the majority gender chosen for the first point in setup, or `null` when ABBA
 * gender tracking is off (the default — the score card looks exactly as it did before this
 * existed). When set, [currentGender] derives the current point's ratio from it.
 */
data class GameState(
    val history: List<ScoreEvent> = emptyList(),
    val usTeam: TeamConfig = TeamConfig.DEFAULT_US,
    val themTeam: TeamConfig = TeamConfig.DEFAULT_THEM,
    val abbaStart: Gender? = null,
) {
    val us: Int get() = history.count { it.team == Team.US }
    val them: Int get() = history.count { it.team == Team.THEM }
    val canUndo: Boolean get() = history.isNotEmpty()

    /** 1-indexed number of the point being played right now — the one the next score completes. */
    val currentPoint: Int get() = history.size + 1

    /** Majority gender for [currentPoint] per the ABBA rule, or `null` when tracking is off. */
    val currentGender: Gender? get() = abbaStart?.let { genderForPoint(currentPoint, it) }

    fun configFor(team: Team): TeamConfig = when (team) {
        Team.US -> usTeam
        Team.THEM -> themTeam
    }

    fun scoreFor(team: Team): Int = when (team) {
        Team.US -> us
        Team.THEM -> them
    }
}
