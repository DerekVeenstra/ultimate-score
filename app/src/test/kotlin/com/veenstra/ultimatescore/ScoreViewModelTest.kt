package com.veenstra.ultimatescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * These exercise the ViewModel -> StateFlow wiring only. The scoring/undo/new-game logic itself
 * is covered exhaustively in GameReducerTest against the pure reducer.
 */
class ScoreViewModelTest {

    private fun viewModel(startAt: Long = 0L): ScoreViewModel {
        var now = startAt
        return ScoreViewModel(clock = { now++ })
    }

    @Test
    fun `initial state is zero-zero`() {
        val vm = viewModel()
        assertEquals(GameState(), vm.state.value)
    }

    @Test
    fun `score updates the exposed state`() {
        val vm = viewModel()
        vm.score(Team.US)
        vm.score(Team.US)
        vm.score(Team.THEM)
        assertEquals(2, vm.state.value.us)
        assertEquals(1, vm.state.value.them)
    }

    @Test
    fun `undo pops the last point from the exposed state`() {
        val vm = viewModel()
        vm.score(Team.US)
        vm.score(Team.THEM)
        vm.undo()
        assertEquals(1, vm.state.value.us)
        assertEquals(0, vm.state.value.them)
    }

    @Test
    fun `new game resets the exposed state`() {
        val vm = viewModel()
        vm.score(Team.US)
        vm.score(Team.THEM)
        vm.newGame()
        assertEquals(GameState(), vm.state.value)
        assertFalse(vm.state.value.canUndo)
    }
}
