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

/**
 * Where [ScoreViewModel] loads and persists the two preset lists (PLAN.md section 13). Separate
 * from [ScoreHistoryStore] rather than folded into it because a preset add/rename/recolour/delete
 * only ever needs to touch one of the two lists — no reason to re-encode and rewrite the other one,
 * or the running game's history, every time. Optional/nullable the same way [ScoreHistoryStore] is
 * on [ScoreViewModel], so JVM tests can construct the ViewModel with no Android dependency.
 */
interface TeamPresetStore {
    suspend fun loadPresets(): TeamPresetLists
    suspend fun savePresets(group: PresetGroup, presets: List<TeamPreset>)
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
 * The stored ABBA starting gender, or `null` — which covers both "ABBA tracking is off" and a
 * game saved before this feature existed (no key at all), and any unrecognised value.
 */
internal fun decodeGender(raw: String?): Gender? =
    raw?.let { name -> runCatching { Gender.valueOf(name) }.getOrNull() }

/**
 * Rebuilds a team's identity from storage. A game saved before team names/colours existed has
 * neither key, and correctly comes back as [fallback] — the plain "US"/"THEM" look.
 */
internal fun decodeTeam(name: String?, color: String?, fallback: TeamConfig): TeamConfig =
    TeamConfig.named(name = name, color = decodeColor(color), fallback = fallback)

/**
 * Field/record separators for [encodePresets]/[decodePresets]. Preset names are arbitrary user
 * text and *will* contain commas and colons — the punctuation the history/team codecs above use
 * as separators — so reusing those would require an escaping scheme (and its bugs). ASCII control
 * characters that can never appear in a name are used instead: [sanitizeName] strips
 * every control character from a name before it's ever stored, so these two can never collide
 * with real content, and no escaping is needed at all.
 */
private const val PRESET_FIELD_SEPARATOR = "\u001F" // Unit Separator
private const val PRESET_RECORD_SEPARATOR = "\u001E" // Record Separator

/**
 * One preset per record (`id`[US]`name`[US]`color`), records joined by RS — see
 * [PRESET_FIELD_SEPARATOR]/[PRESET_RECORD_SEPARATOR]. A watch-local list of teams is at most a
 * handful of entries, so, same reasoning as [encodeHistory], cost is irrelevant.
 */
internal fun encodePresets(presets: List<TeamPreset>): String =
    presets.joinToString(separator = PRESET_RECORD_SEPARATOR) { preset ->
        listOf(preset.id, preset.name, preset.color.name).joinToString(PRESET_FIELD_SEPARATOR)
    }

internal fun decodePresets(raw: String?): List<TeamPreset> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(PRESET_RECORD_SEPARATOR).mapNotNull(::decodeTeamPreset)
}

/** Malformed entries (wrong field count, blank id/name) are dropped rather than crashing. */
private fun decodeTeamPreset(entry: String): TeamPreset? {
    val parts = entry.split(PRESET_FIELD_SEPARATOR)
    if (parts.size != 3) return null
    val id = parts[0]
    val name = parts[1]
    if (id.isBlank() || name.isBlank()) return null
    return TeamPreset(id = id, name = name, color = decodeColor(parts[2]))
}

// ---------------------------------------------------------------------------------------------

private const val DATASTORE_NAME = "game_state"
private val HISTORY_KEY = stringPreferencesKey("history")
private val US_NAME_KEY = stringPreferencesKey("us_name")
private val US_COLOR_KEY = stringPreferencesKey("us_color")
private val THEM_NAME_KEY = stringPreferencesKey("them_name")
private val THEM_COLOR_KEY = stringPreferencesKey("them_color")
private val ABBA_START_KEY = stringPreferencesKey("abba_start")
private val MY_TEAM_PRESETS_KEY = stringPreferencesKey("my_team_presets")
private val OPPONENT_PRESETS_KEY = stringPreferencesKey("opponent_team_presets")
private val Context.gameDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

private fun presetsKeyFor(group: PresetGroup) = when (group) {
    PresetGroup.MY_TEAMS -> MY_TEAM_PRESETS_KEY
    PresetGroup.OPPONENTS -> OPPONENT_PRESETS_KEY
}

/**
 * Persists the game (the score's event log plus both teams' names and colours) and the two
 * preset lists to disk via Jetpack DataStore, so a crash, force-stop, or battery pull never loses
 * the game in progress or a saved team. Both stores share the same `game_state` preferences file
 * — there's no reason to split it, and this keeps everything watch-local in one place. See
 * PLAN.md section 4 "Persistence", section 6 Phase 4, and section 13 (presets).
 */
class DataStoreScoreRepository(private val context: Context) : ScoreHistoryStore, TeamPresetStore {

    override suspend fun load(): GameState {
        val prefs = context.gameDataStore.data.first()
        return GameState(
            history = decodeHistory(prefs[HISTORY_KEY]),
            usTeam = decodeTeam(prefs[US_NAME_KEY], prefs[US_COLOR_KEY], TeamConfig.DEFAULT_US),
            themTeam = decodeTeam(prefs[THEM_NAME_KEY], prefs[THEM_COLOR_KEY], TeamConfig.DEFAULT_THEM),
            abbaStart = decodeGender(prefs[ABBA_START_KEY]),
        )
    }

    override suspend fun save(state: GameState) {
        context.gameDataStore.edit { prefs ->
            prefs[HISTORY_KEY] = encodeHistory(state.history)
            prefs[US_NAME_KEY] = state.usTeam.name
            prefs[US_COLOR_KEY] = state.usTeam.color.name
            prefs[THEM_NAME_KEY] = state.themTeam.name
            prefs[THEM_COLOR_KEY] = state.themTeam.color.name
            // Off is the absence of the key, not a stored sentinel — so a game saved before this
            // feature and one with ABBA deliberately off read back identically (decodeGender null).
            state.abbaStart?.let { prefs[ABBA_START_KEY] = it.name } ?: prefs.remove(ABBA_START_KEY)
        }
    }

    override suspend fun loadPresets(): TeamPresetLists {
        val prefs = context.gameDataStore.data.first()
        return TeamPresetLists(
            myTeams = decodePresets(prefs[MY_TEAM_PRESETS_KEY]),
            opponents = decodePresets(prefs[OPPONENT_PRESETS_KEY]),
        )
    }

    override suspend fun savePresets(group: PresetGroup, presets: List<TeamPreset>) {
        context.gameDataStore.edit { prefs ->
            prefs[presetsKeyFor(group)] = encodePresets(presets)
        }
    }
}
