package com.veenstra.ultimatescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The team-identity model and how [reduce] carries it through a game. */
class TeamConfigTest {

    @Test
    fun `a new game defaults to the plain US-THEM, no-colour identities`() {
        val state = GameState()
        assertEquals("US", state.usTeam.name)
        assertEquals("THEM", state.themTeam.name)
        assertEquals(TeamColor.NONE, state.usTeam.color)
        assertEquals(TeamColor.NONE, state.themTeam.color)
    }

    @Test
    fun `new game adopts the chosen team names and colours`() {
        val us = TeamConfig("Flaming Nipples", TeamColor.PINK)
        val them = TeamConfig("Sockeye", TeamColor.BLUE)

        val state = reduce(GameState(), GameAction.NewGame(us, them), now = 1000)

        assertEquals(us, state.usTeam)
        assertEquals(them, state.themTeam)
        assertEquals(0, state.us)
        assertEquals(0, state.them)
    }

    @Test
    fun `new game with no chosen teams resets to the defaults`() {
        val customised = GameState(
            usTeam = TeamConfig("Flaming Throws", TeamColor.GRAY),
            themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
        )

        val state = reduce(customised, GameAction.NewGame(), now = 1000)

        assertEquals(TeamConfig.DEFAULT_US, state.usTeam)
        assertEquals(TeamConfig.DEFAULT_THEM, state.themTeam)
    }

    @Test
    fun `scoring and undo leave the team identities untouched`() {
        val us = TeamConfig("Flaming Nipples", TeamColor.PINK)
        val them = TeamConfig("Sockeye", TeamColor.BLUE)
        var state = reduce(GameState(), GameAction.NewGame(us, them), now = 1000)

        state = reduce(state, GameAction.Score(Team.US), now = 2000)
        state = reduce(state, GameAction.Score(Team.THEM), now = 3000)
        state = reduce(state, GameAction.Undo, now = 4000)

        assertEquals(us, state.usTeam)
        assertEquals(them, state.themTeam)
        assertEquals(1, state.us)
        assertEquals(0, state.them)
    }

    @Test
    fun `configFor and scoreFor read the right side`() {
        val us = TeamConfig("Flaming Throws", TeamColor.GRAY)
        val them = TeamConfig("Sockeye", TeamColor.BLUE)
        var state = reduce(GameState(), GameAction.NewGame(us, them), now = 1000)
        state = reduce(state, GameAction.Score(Team.US), now = 2000)
        state = reduce(state, GameAction.Score(Team.US), now = 3000)
        state = reduce(state, GameAction.Score(Team.THEM), now = 4000)

        assertEquals(us, state.configFor(Team.US))
        assertEquals(them, state.configFor(Team.THEM))
        assertEquals(2, state.scoreFor(Team.US))
        assertEquals(1, state.scoreFor(Team.THEM))
    }

    @Test
    fun `a blank or whitespace-only name falls back to the default rather than rendering empty`() {
        assertEquals("THEM", TeamConfig.named("", TeamColor.RED, TeamConfig.DEFAULT_THEM).name)
        assertEquals("THEM", TeamConfig.named("   ", TeamColor.RED, TeamConfig.DEFAULT_THEM).name)
        assertEquals("THEM", TeamConfig.named(null, TeamColor.RED, TeamConfig.DEFAULT_THEM).name)
        // ...but the chosen colour is still kept.
        assertEquals(TeamColor.RED, TeamConfig.named("", TeamColor.RED, TeamConfig.DEFAULT_THEM).color)
    }

    @Test
    fun `a typed name is trimmed`() {
        val config = TeamConfig.named("  Sockeye  ", TeamColor.BLUE, TeamConfig.DEFAULT_THEM)
        assertEquals("Sockeye", config.name)
    }

    @Test
    fun `the two requested US presets exist with their colours`() {
        val presets = TeamConfig.US_PRESETS
        assertTrue(presets.contains(TeamConfig("Flaming Nipples", TeamColor.PINK)))
        assertTrue(presets.contains(TeamConfig("Flaming Throws", TeamColor.GRAY)))
        assertTrue(presets.contains(TeamConfig.DEFAULT_US))
    }

    @Test
    fun `no colour means a pure black background, unchanged from before the feature`() {
        assertEquals(0xFF000000, TeamColor.NONE.backgroundArgb)
    }

    @Test
    fun `every other colour's background is its true, full-saturation swatch`() {
        // Derek tried the earlier deep-tint version and preferred true colours (PLAN.md
        // section 11) — the background is now exactly the pickable swatch, not a muted version
        // of it.
        (TeamColor.entries - TeamColor.NONE).forEach { color ->
            assertEquals(color.swatchArgb, color.backgroundArgb)
        }
    }

    @Test
    fun `every colour has a text colour that meets WCAG AA contrast against its background`() {
        // True-colour backgrounds (unlike the old deep tint) can be light enough that white text
        // would fail outright — this is the actual legibility guarantee now: whichever of
        // black/white contrasts best is picked per background, and it must clear the WCAG AA bar
        // for large/bold text (3:1) at minimum. Real numeral text is large and bold, so 3:1 is
        // the applicable threshold, not the stricter 4.5:1 for small body text.
        TeamColor.entries.forEach { color ->
            val bg = color.backgroundArgb
            val textColor = textColorArgbFor(bg)
            val contrast = contrastRatio(bg, textColor)
            assertTrue(
                "${color.name}: background 0x${bg.toString(16)} with text 0x${textColor.toString(16)} " +
                    "only has $contrast:1 contrast",
                contrast >= 3.0,
            )
        }
    }

    @Test
    fun `black background picks white text and white background picks black text`() {
        assertEquals(0xFFFFFFFF, textColorArgbFor(0xFF000000))
        assertEquals(0xFF000000, textColorArgbFor(0xFFFFFFFF))
    }

    @Test
    fun `light colours like gray and orange pick black text, not white`() {
        // The concrete case that motivated computing this rather than assuming white: at full
        // saturation these are light enough that white numerals would be hard to read.
        assertEquals(0xFF000000, textColorArgbFor(TeamColor.GRAY.backgroundArgb))
        assertEquals(0xFF000000, textColorArgbFor(TeamColor.ORANGE.backgroundArgb))
    }
}
