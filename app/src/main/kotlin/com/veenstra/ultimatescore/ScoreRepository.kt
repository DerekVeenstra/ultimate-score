package com.veenstra.ultimatescore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Where [ScoreViewModel] loads and saves the event log. An interface (rather than a concrete
 * DataStore class) so the ViewModel stays constructible with no Android framework dependency in
 * plain JVM unit tests — see ScoreViewModelPersistenceTest's in-memory fake.
 */
interface ScoreHistoryStore {
    suspend fun loadHistory(): List<ScoreEvent>
    suspend fun saveHistory(history: List<ScoreEvent>)
}

private const val DATASTORE_NAME = "game_state"
private val HISTORY_KEY = stringPreferencesKey("history")
private val Context.gameDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

/**
 * Persists the score's event log (see GameState.kt) to disk via Jetpack DataStore, so a crash,
 * force-stop, or battery pull never loses the game in progress. See PLAN.md section 4
 * "Persistence" and section 6 Phase 4.
 *
 * Encoding is the plan's "compact string" option rather than JSON — one comma-separated
 * `TEAM:MILLIS` pair per point, e.g. `US:1699000000000,THEM:1699000100000`. A game is at most a
 * few dozen points, so this is trivially cheap to write on every single change.
 */
class DataStoreScoreRepository(private val context: Context) : ScoreHistoryStore {

    override suspend fun loadHistory(): List<ScoreEvent> =
        decode(context.gameDataStore.data.first()[HISTORY_KEY].orEmpty())

    override suspend fun saveHistory(history: List<ScoreEvent>) {
        context.gameDataStore.edit { it[HISTORY_KEY] = encode(history) }
    }

    private fun encode(history: List<ScoreEvent>): String =
        history.joinToString(separator = ",") { "${it.team.name}:${it.atMillis}" }

    private fun decode(raw: String): List<ScoreEvent> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull(::decodeEntry)
    }

    /** Malformed entries are dropped rather than crashing the app over a corrupt preference. */
    private fun decodeEntry(entry: String): ScoreEvent? {
        val parts = entry.split(":")
        if (parts.size != 2) return null
        val team = runCatching { Team.valueOf(parts[0]) }.getOrNull() ?: return null
        val millis = parts[1].toLongOrNull() ?: return null
        return ScoreEvent(team, millis)
    }
}
