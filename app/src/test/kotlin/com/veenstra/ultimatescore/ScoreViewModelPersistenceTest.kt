package com.veenstra.ultimatescore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises ScoreViewModel's interaction with [ScoreHistoryStore] using an in-memory fake — no
 * Android framework or real DataStore involved. The real DataStore-backed implementation
 * (DataStoreScoreRepository) can only be verified on-device/emulator, which is done manually per
 * PLAN.md Phase 4's "done when" (force-stop and reopen restores the exact score).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModelPersistenceTest {

    @Before
    fun setUp() {
        // viewModelScope uses Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so loads/saves complete synchronously within each test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with no store, is ready immediately with an empty game`() {
        val vm = ScoreViewModel()
        assertTrue(vm.isReady.value)
        assertEquals(GameState(), vm.state.value)
    }

    @Test
    fun `loads persisted history on start and becomes ready`() {
        val store = FakeScoreHistoryStore(
            initial = listOf(ScoreEvent(Team.US, 1000), ScoreEvent(Team.US, 2000), ScoreEvent(Team.THEM, 3000)),
        )
        val vm = ScoreViewModel(historyStore = store)

        assertTrue(vm.isReady.value)
        assertEquals(2, vm.state.value.us)
        assertEquals(1, vm.state.value.them)
    }

    @Test
    fun `scoring persists the updated history`() {
        val store = FakeScoreHistoryStore()
        var now = 0L
        val vm = ScoreViewModel(historyStore = store, clock = { now++ })

        vm.score(Team.US)
        assertEquals(listOf(ScoreEvent(Team.US, 0)), store.saved)

        vm.score(Team.THEM)
        assertEquals(listOf(ScoreEvent(Team.US, 0), ScoreEvent(Team.THEM, 1)), store.saved)
    }

    @Test
    fun `undo persists the popped history`() {
        val store = FakeScoreHistoryStore()
        val vm = ScoreViewModel(historyStore = store, clock = { 0L })

        vm.score(Team.US)
        vm.undo()

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `new game persists an empty history`() {
        val store = FakeScoreHistoryStore()
        val vm = ScoreViewModel(historyStore = store, clock = { 0L })

        vm.score(Team.US)
        vm.score(Team.THEM)
        vm.newGame()

        assertTrue(store.saved.isEmpty())
    }
}

private class FakeScoreHistoryStore(
    private val initial: List<ScoreEvent> = emptyList(),
) : ScoreHistoryStore {
    var saved: List<ScoreEvent> = initial
        private set

    override suspend fun loadHistory(): List<ScoreEvent> = initial

    override suspend fun saveHistory(history: List<ScoreEvent>) {
        saved = history
    }
}
