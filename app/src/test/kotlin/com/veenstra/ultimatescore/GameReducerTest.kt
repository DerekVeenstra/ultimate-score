package com.veenstra.ultimatescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReducerTest {

    @Test
    fun `new game starts at zero-zero with undo disabled`() {
        val state = GameState()
        assertEquals(0, state.us)
        assertEquals(0, state.them)
        assertFalse(state.canUndo)
    }

    @Test
    fun `scoring US increments us only`() {
        val state = reduce(GameState(), GameAction.Score(Team.US), now = 1000)
        assertEquals(1, state.us)
        assertEquals(0, state.them)
        assertTrue(state.canUndo)
    }

    @Test
    fun `scoring THEM increments them only`() {
        val state = reduce(GameState(), GameAction.Score(Team.THEM), now = 1000)
        assertEquals(0, state.us)
        assertEquals(1, state.them)
    }

    @Test
    fun `scoring both teams tracks independent tallies`() {
        var state = GameState()
        state = reduce(state, GameAction.Score(Team.US), now = 1000)
        state = reduce(state, GameAction.Score(Team.US), now = 2000)
        state = reduce(state, GameAction.Score(Team.THEM), now = 3000)
        assertEquals(2, state.us)
        assertEquals(1, state.them)
    }

    @Test
    fun `undo removes only the most recent point`() {
        var state = GameState()
        state = reduce(state, GameAction.Score(Team.US), now = 1000)
        state = reduce(state, GameAction.Score(Team.THEM), now = 2000)
        state = reduce(state, GameAction.Undo, now = 3000)
        assertEquals(1, state.us)
        assertEquals(0, state.them)
    }

    @Test
    fun `undo restores the exact prior state, not just the count`() {
        var state = GameState()
        state = reduce(state, GameAction.Score(Team.US), now = 1000)
        val afterFirstPoint = state
        state = reduce(state, GameAction.Score(Team.THEM), now = 2000)
        state = reduce(state, GameAction.Undo, now = 3000)
        assertEquals(afterFirstPoint, state)
    }

    @Test
    fun `undo on an empty game is a no-op`() {
        val empty = GameState()
        val afterUndo = reduce(empty, GameAction.Undo, now = 1000)
        assertEquals(empty, afterUndo)
        assertFalse(afterUndo.canUndo)
    }

    @Test
    fun `undo then score behaves as if the undone point never happened`() {
        var state = GameState()
        state = reduce(state, GameAction.Score(Team.US), now = 1000) // US 1-0
        state = reduce(state, GameAction.Undo, now = 2000) // back to 0-0
        state = reduce(state, GameAction.Score(Team.THEM), now = 3000) // THEM 0-1
        assertEquals(0, state.us)
        assertEquals(1, state.them)
        assertEquals(1, state.history.size)
    }

    @Test
    fun `ABBA is off by default and shows no current gender`() {
        assertEquals(null, GameState().abbaStart)
        assertEquals(null, GameState().currentGender)
    }

    @Test
    fun `new game carries the chosen ABBA starting gender`() {
        val state = reduce(GameState(), GameAction.NewGame(abbaStart = Gender.F), now = 1000)
        assertEquals(Gender.F, state.abbaStart)
    }

    @Test
    fun `genderForPoint follows the ABBA pattern from an M start`() {
        // A B B A A B B A A B ...
        val expected = listOf(
            Gender.M, Gender.F, Gender.F, Gender.M, Gender.M,
            Gender.F, Gender.F, Gender.M, Gender.M, Gender.F,
        )
        val actual = (1..10).map { genderForPoint(it, Gender.M) }
        assertEquals(expected, actual)
    }

    @Test
    fun `genderForPoint from an F start is the mirror image`() {
        (1..12).forEach { point ->
            assertEquals(genderForPoint(point, Gender.M).opposite(), genderForPoint(point, Gender.F))
        }
    }

    @Test
    fun `currentGender tracks the point in progress as the score climbs`() {
        var state = reduce(GameState(), GameAction.NewGame(abbaStart = Gender.M), now = 0)
        assertEquals(Gender.M, state.currentGender) // point 1
        state = reduce(state, GameAction.Score(Team.US), now = 1) // point 2 in progress
        assertEquals(Gender.F, state.currentGender)
        state = reduce(state, GameAction.Score(Team.THEM), now = 2) // point 3
        assertEquals(Gender.F, state.currentGender)
        state = reduce(state, GameAction.Score(Team.US), now = 3) // point 4
        assertEquals(Gender.M, state.currentGender)
        state = reduce(state, GameAction.Undo, now = 4) // back to point 3
        assertEquals(Gender.F, state.currentGender)
    }

    @Test
    fun `new game clears history regardless of prior score`() {
        var state = GameState()
        state = reduce(state, GameAction.Score(Team.US), now = 1000)
        state = reduce(state, GameAction.Score(Team.US), now = 2000)
        state = reduce(state, GameAction.Score(Team.THEM), now = 3000)
        state = reduce(state, GameAction.NewGame(), now = 4000)
        assertEquals(GameState(), state)
    }
}
