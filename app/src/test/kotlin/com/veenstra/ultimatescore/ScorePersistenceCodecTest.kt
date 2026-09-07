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
}
