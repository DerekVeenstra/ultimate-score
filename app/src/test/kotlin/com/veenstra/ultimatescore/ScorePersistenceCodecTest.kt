package com.veenstra.ultimatescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure serialization used by DataStoreScoreRepository. Kept free of Android imports precisely
 * so this — including how a game saved *before* team names/colours existed is read back — can be
 * covered without an emulator.
 */
class ScorePersistenceCodecTest {

    @Test
    fun `history survives an encode-decode round trip`() {
        val history = listOf(
            ScoreEvent(Team.US, 1_699_000_000_000),
            ScoreEvent(Team.THEM, 1_699_000_100_000),
            ScoreEvent(Team.US, 1_699_000_200_000),
        )
        assertEquals(history, decodeHistory(encodeHistory(history)))
    }

    @Test
    fun `an empty history round trips to empty`() {
        assertEquals(emptyList<ScoreEvent>(), decodeHistory(encodeHistory(emptyList())))
    }

    @Test
    fun `absent or blank stored history decodes to an empty game, not a crash`() {
        assertEquals(emptyList<ScoreEvent>(), decodeHistory(null))
        assertEquals(emptyList<ScoreEvent>(), decodeHistory(""))
        assertEquals(emptyList<ScoreEvent>(), decodeHistory("   "))
    }

    @Test
    fun `corrupt history entries are dropped rather than crashing the app`() {
        val raw = "US:1000,GARBAGE,THEM:notanumber,THEM:2000,:,US:"
        assertEquals(
            listOf(ScoreEvent(Team.US, 1000), ScoreEvent(Team.THEM, 2000)),
            decodeHistory(raw),
        )
    }

    @Test
    fun `colours round trip by name`() {
        TeamColor.entries.forEach { color ->
            assertEquals(color, decodeColor(color.name))
        }
    }

    @Test
    fun `an unknown or absent colour falls back to none`() {
        assertEquals(TeamColor.NONE, decodeColor(null))
        assertEquals(TeamColor.NONE, decodeColor(""))
        assertEquals(TeamColor.NONE, decodeColor("CHARTREUSE"))
    }

    @Test
    fun `the ABBA starting gender round trips by name`() {
        Gender.entries.forEach { gender ->
            assertEquals(gender, decodeGender(gender.name))
        }
    }

    @Test
    fun `an unknown, absent, or blank ABBA gender decodes to null`() {
        assertEquals(null, decodeGender(null))
        assertEquals(null, decodeGender(""))
        assertEquals(null, decodeGender("X"))
        assertEquals(null, decodeGender("MALE"))
    }

    @Test
    fun `a team round trips its name and colour`() {
        val decoded = decodeTeam("Flaming Nipples", "PINK", TeamConfig.DEFAULT_US)
        assertEquals(TeamConfig("Flaming Nipples", TeamColor.PINK), decoded)
    }

    @Test
    fun `a game saved before team colours existed loads as the plain US-THEM look`() {
        // The real backward-compatibility case: an install that already has a game in progress
        // has a `history` key but no team keys at all. It must come back as the old appearance,
        // with the score intact — not blank names or a crash.
        val us = decodeTeam(name = null, color = null, fallback = TeamConfig.DEFAULT_US)
        val them = decodeTeam(name = null, color = null, fallback = TeamConfig.DEFAULT_THEM)

        assertEquals(TeamConfig.DEFAULT_US, us)
        assertEquals(TeamConfig.DEFAULT_THEM, them)
        assertEquals(TeamColor.NONE, us.color)

        val history = decodeHistory("US:1000,THEM:2000,US:3000")
        val restored = GameState(history = history, usTeam = us, themTeam = them)
        assertEquals(2, restored.us)
        assertEquals(1, restored.them)
        assertTrue(restored.canUndo)
    }

    // -----------------------------------------------------------------------------------------
    // Preset codec (PLAN.md section 13). Control characters, not commas/colons, are the field
    // ([U+001F]) and record ([U+001E]) separators specifically because preset names are arbitrary
    // user text that will contain commas and colons — the separators every other codec above
    // uses — and control characters are the one class of character sanitizeName() guarantees a
    // stored name can never contain.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a single preset survives an encode-decode round trip`() {
        val preset = TeamPreset(id = "1699000000000", name = "Flaming Nipples", color = TeamColor.PINK)
        assertEquals(listOf(preset), decodePresets(encodePresets(listOf(preset))))
    }

    @Test
    fun `multiple presets survive an encode-decode round trip in order`() {
        val presets = listOf(
            TeamPreset("1", "Flaming Nipples", TeamColor.PINK),
            TeamPreset("2", "Flaming Throws", TeamColor.GRAY),
            TeamPreset("3", "Sockeye", TeamColor.NONE),
        )
        assertEquals(presets, decodePresets(encodePresets(presets)))
    }

    @Test
    fun `an empty preset list round trips to empty`() {
        assertEquals(emptyList<TeamPreset>(), decodePresets(encodePresets(emptyList())))
    }

    @Test
    fun `absent or blank stored presets decode to an empty list, not a crash`() {
        assertEquals(emptyList<TeamPreset>(), decodePresets(null))
        assertEquals(emptyList<TeamPreset>(), decodePresets(""))
        assertEquals(emptyList<TeamPreset>(), decodePresets("   "))
    }

    @Test
    fun `preset names containing commas and colons round trip exactly`() {
        // The concrete case that ruled out reusing the history/team codecs' `,`/`:` separators —
        // see the class-level comment above this section.
        val preset = TeamPreset(id = "1", name = "Sockeye, F.C.: The Sequel", color = TeamColor.BLUE)
        assertEquals(listOf(preset), decodePresets(encodePresets(listOf(preset))))
    }

    @Test
    fun `a name containing the codec's own separator characters is stored stripped`() {
        // sanitizeName() (TeamConfig.kt) strips control characters before a preset is ever built,
        // so this is really asserting that the two layers cooperate correctly: a name that snuck
        // a raw separator character past sanitizeName (which shouldn't happen through the real
        // creation path, but this pins the contract) still round trips using whatever survives
        // splitting on it, rather than corrupting neighbouring fields or throwing.
        val sanitizedName = sanitizeName("Sock\u001Feye\u001EF.C.")
        val preset = TeamPreset(id = "1", name = sanitizedName, color = TeamColor.RED)
        assertEquals(listOf(preset), decodePresets(encodePresets(listOf(preset))))
    }

    @Test
    fun `malformed preset records are dropped rather than crashing the app`() {
        val valid = TeamPreset("2", "Sockeye", TeamColor.BLUE)
        val raw = listOf(
            "1\u001F\u001FPINK", // blank name
            "\u001FNoId\u001FBLUE", // blank id
            "onlyonefield", // wrong field count
            "${valid.id}\u001F${valid.name}\u001F${valid.color.name}",
        ).joinToString("\u001E")

        assertEquals(listOf(valid), decodePresets(raw))
    }

    @Test
    fun `a preset with an unknown colour name falls back to NONE rather than dropping the preset`() {
        val raw = "1\u001FSockeye\u001FCHARTREUSE"
        assertEquals(listOf(TeamPreset("1", "Sockeye", TeamColor.NONE)), decodePresets(raw))
    }

    // -----------------------------------------------------------------------------------------
    // Saved-game codec (the "Done" score-history feature). Same field/record separators and same
    // reasoning as the preset codec above -- team names in a saved game go through the same
    // sanitizeName() path a preset's does, so they can never contain the separator characters.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a single saved game survives an encode-decode round trip`() {
        val game = SavedGame(
            id = "1699000000000",
            usTeam = TeamConfig("Flaming Nipples", TeamColor.PINK),
            themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
            usScore = 12,
            themScore = 9,
            completedAtMillis = 1_699_000_300_000,
        )
        assertEquals(listOf(game), decodeSavedGames(encodeSavedGames(listOf(game))))
    }

    @Test
    fun `multiple saved games survive an encode-decode round trip in order`() {
        val games = listOf(
            SavedGame("1", TeamConfig.DEFAULT_US, TeamConfig.DEFAULT_THEM, 12, 9, 1000),
            SavedGame("2", TeamConfig("Flaming Throws", TeamColor.GRAY), TeamConfig("Sockeye", TeamColor.BLUE), 8, 15, 2000),
        )
        assertEquals(games, decodeSavedGames(encodeSavedGames(games)))
    }

    @Test
    fun `an empty saved-game list round trips to empty`() {
        assertEquals(emptyList<SavedGame>(), decodeSavedGames(encodeSavedGames(emptyList())))
    }

    @Test
    fun `absent or blank stored saved games decode to an empty list, not a crash`() {
        assertEquals(emptyList<SavedGame>(), decodeSavedGames(null))
        assertEquals(emptyList<SavedGame>(), decodeSavedGames(""))
        assertEquals(emptyList<SavedGame>(), decodeSavedGames("   "))
    }

    @Test
    fun `team names containing commas and colons round trip exactly in a saved game`() {
        val game = SavedGame(
            id = "1",
            usTeam = TeamConfig("Sockeye, F.C.: The Sequel", TeamColor.BLUE),
            themTeam = TeamConfig.DEFAULT_THEM,
            usScore = 3,
            themScore = 1,
            completedAtMillis = 5000,
        )
        assertEquals(listOf(game), decodeSavedGames(encodeSavedGames(listOf(game))))
    }

    @Test
    fun `malformed saved-game records are dropped rather than crashing the app`() {
        val valid = SavedGame("2", TeamConfig.DEFAULT_US, TeamConfig("Sockeye", TeamColor.BLUE), 5, 4, 9000)
        val raw = listOf(
            "1\u001FUS\u001FNONE\u001FTHEM\u001FNONE\u001Fnotanumber\u001F4\u001F9000", // bad us score
            "\u001FUS\u001FNONE\u001FTHEM\u001FNONE\u001F5\u001F4\u001F9000", // blank id
            "onlyonefield", // wrong field count
            listOf(
                valid.id,
                valid.usTeam.name,
                valid.usTeam.color.name,
                valid.themTeam.name,
                valid.themTeam.color.name,
                valid.usScore.toString(),
                valid.themScore.toString(),
                valid.completedAtMillis.toString(),
            ).joinToString("\u001F"),
        ).joinToString("\u001E")

        assertEquals(listOf(valid), decodeSavedGames(raw))
    }

    @Test
    fun `a saved game with an unknown colour name falls back to NONE rather than dropping it`() {
        val raw = "1\u001FSockeye\u001FCHARTREUSE\u001FTHEM\u001FNONE\u001F5\u001F4\u001F9000"
        val decoded = decodeSavedGames(raw).single()
        assertEquals(TeamColor.NONE, decoded.usTeam.color)
    }

    @Test
    fun `a saved game round trips its final score exactly, including a 0-0 result`() {
        val game = SavedGame("1", TeamConfig.DEFAULT_US, TeamConfig.DEFAULT_THEM, 0, 0, 1000)
        assertEquals(listOf(game), decodeSavedGames(encodeSavedGames(listOf(game))))
    }
}
