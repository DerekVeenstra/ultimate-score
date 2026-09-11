package com.veenstra.ultimatescore

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A completed game archived to score history when the player confirms the "Done" control on the
 * score card (see ScoreScreen's `EndGameConfirmScreen` and NewGameSetupScreen's "Score history"
 * section). Deliberately just the final score and each team's identity at the moment the game
 * ended — not the full point-by-point [ScoreEvent] log — since the history section is a plain
 * list with no per-game detail view (nothing today would use a fuller log).
 *
 * [id] is generated at creation time by the caller ([ScoreViewModel]), the same pattern
 * [TeamPreset.id] uses, so tests can supply a deterministic generator instead of the real
 * wall-clock one, and so deleting one saved game can never be confused with another.
 */
data class SavedGame(
    val id: String,
    val usTeam: TeamConfig,
    val themTeam: TeamConfig,
    val usScore: Int,
    val themScore: Int,
    val completedAtMillis: Long,
)

private val SAVED_GAME_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

/**
 * A short human-readable "when this game ended" string for a score-history row, e.g.
 * "Sep 10, 3:40 PM". [zone] defaults to the device's real zone but is a parameter so this stays
 * a pure, deterministic function for JVM unit tests (see ScorePersistenceCodecTest).
 */
internal fun formatSavedGameTimestamp(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(SAVED_GAME_TIMESTAMP_FORMAT)
