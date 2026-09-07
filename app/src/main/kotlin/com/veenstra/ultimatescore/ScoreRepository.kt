package com.veenstra.ultimatescore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Where [ScoreViewModel] loads and saves the game. An interface (rather than a concrete DataStore
 * class) so the ViewModel stays constructible with no Android framework dependency in plain JVM
 * unit tests — see ScoreViewModelPersistenceTest's in-memory fake.
 */
interface ScoreHistoryStore {
    suspend fun load(): GameState
    suspend fun save(state: GameState)
}

// ---------------------------------------------------------------------------------------------
// Pure codecs. Top-level and free of Android imports specifically so the serialization round-trip
// — including how already-saved games from before team colours existed are read back — is
// covered by plain JVM unit tests (see ScorePersistenceCodecTest).
// ---------------------------------------------------------------------------------------------

/**
 * The plan's "compact string" encoding rather than JSON — one comma-separated `TEAM:MILLIS` pair
 * per point, e.g. `US:1699000000000,THEM:1699000100000`. A game is at most a few dozen points, so
 * this is trivially cheap to write on every single change.
 */
internal fun encodeHistory(history: List<ScoreEvent>): String =
    history.joinToString(separator = ",") { "${it.team.name}:${it.atMillis}" }

internal fun decodeHistory(raw: String?): List<ScoreEvent> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",").mapNotNull(::decodeScoreEvent)
}

/** Malformed entries are dropped rather than crashing the app over a corrupt preference. */
private fun decodeScoreEvent(entry: String): ScoreEvent? {
    val parts = entry.split(":")
    if (parts.size != 2) return null
    val team = runCatching { Team.valueOf(parts[0]) }.getOrNull() ?: return null
    val millis = parts[1].toLongOrNull() ?: return null
    return ScoreEvent(team, millis)
}

/** An unknown or absent colour name falls back to [TeamColor.NONE] rather than throwing. */
internal fun decodeColor(raw: String?): TeamColor =
    raw?.let { name -> runCatching { TeamColor.valueOf(name) }.getOrNull() } ?: TeamColor.NONE

/**
 * Rebuilds a team's identity from storage. A game saved before team names/colours existed has
 * neither key, and correctly comes back as [fallback] — the plain "US"/"THEM" look.
 */
internal fun decodeTeam(name: String?, color: String?, fallback: TeamConfig): TeamConfig =
    TeamConfig.named(name = name, color = decodeColor(color), fallback = fallback)

// ---------------------------------------------------------------------------------------------

private const val DATASTORE_NAME = "game_state"
private val HISTORY_KEY = stringPreferencesKey("history")
private val US_NAME_KEY = stringPreferencesKey("us_name")
private val US_COLOR_KEY = stringPreferencesKey("us_color")
private val THEM_NAME_KEY = stringPreferencesKey("them_name")
private val THEM_COLOR_KEY = stringPreferencesKey("them_color")
private val Context.gameDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

/**
 * Persists the game (the score's event log plus both teams' names and colours) to disk via
 * Jetpack DataStore, so a crash, force-stop, or battery pull never loses the game in progress.
 * See PLAN.md section 4 "Persistence" and section 6 Phase 4.
 */
class DataStoreScoreRepository(private val context: Context) : ScoreHistoryStore {

    override suspend fun load(): GameState {
        val prefs = context.gameDataStore.data.first()
        return GameState(
            history = decodeHistory(prefs[HISTORY_KEY]),
            usTeam = decodeTeam(prefs[US_NAME_KEY], prefs[US_COLOR_KEY], TeamConfig.DEFAULT_US),
            themTeam = decodeTeam(prefs[THEM_NAME_KEY], prefs[THEM_COLOR_KEY], TeamConfig.DEFAULT_THEM),
        )
    }

    override suspend fun save(state: GameState) {
        context.gameDataStore.edit { prefs ->
            prefs[HISTORY_KEY] = encodeHistory(state.history)
            prefs[US_NAME_KEY] = state.usTeam.name
            prefs[US_COLOR_KEY] = state.usTeam.color.name
            prefs[THEM_NAME_KEY] = state.themTeam.name
            prefs[THEM_COLOR_KEY] = state.themTeam.color.name
        }
    }
}
