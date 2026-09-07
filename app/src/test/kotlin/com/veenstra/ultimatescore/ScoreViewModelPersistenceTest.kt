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
            initialHistory = listOf(ScoreEvent(Team.US, 1000), ScoreEvent(Team.US, 2000), ScoreEvent(Team.THEM, 3000)),
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

    @Test
    fun `starting a game with chosen teams persists their names and colours`() {
        val store = FakeScoreHistoryStore()
        val vm = ScoreViewModel(historyStore = store, clock = { 0L })

        vm.newGame(
            usTeam = TeamConfig("Flaming Nipples", TeamColor.PINK),
            themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
        )

        assertEquals(TeamConfig("Flaming Nipples", TeamColor.PINK), store.savedState.usTeam)
        assertEquals(TeamConfig("Sockeye", TeamColor.BLUE), store.savedState.themTeam)
    }

    @Test
    fun `a persisted game restores its team names and colours, not just the score`() {
        val store = FakeScoreHistoryStore(
            initialState = GameState(
                history = listOf(ScoreEvent(Team.US, 1000), ScoreEvent(Team.THEM, 2000)),
                usTeam = TeamConfig("Flaming Throws", TeamColor.GRAY),
                themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
            ),
        )

        val vm = ScoreViewModel(historyStore = store)

        assertEquals("Flaming Throws", vm.state.value.usTeam.name)
        assertEquals(TeamColor.GRAY, vm.state.value.usTeam.color)
        assertEquals("Sockeye", vm.state.value.themTeam.name)
        assertEquals(TeamColor.BLUE, vm.state.value.themTeam.color)
        assertEquals(1, vm.state.value.us)
        assertEquals(1, vm.state.value.them)
    }

    @Test
    fun `scoring after choosing teams keeps persisting the team identities`() {
        val store = FakeScoreHistoryStore()
        val vm = ScoreViewModel(historyStore = store, clock = { 0L })
        vm.newGame(usTeam = TeamConfig("Flaming Nipples", TeamColor.PINK))

        vm.score(Team.US)

        assertEquals("Flaming Nipples", store.savedState.usTeam.name)
        assertEquals(1, store.savedState.us)
    }
}

private class FakeScoreHistoryStore(
    initialHistory: List<ScoreEvent> = emptyList(),
    private val initialState: GameState = GameState(history = initialHistory),
) : ScoreHistoryStore {
    var savedState: GameState = initialState
        private set

    /** Convenience for the many assertions that only care about the persisted event log. */
    val saved: List<ScoreEvent> get() = savedState.history

    override suspend fun load(): GameState = initialState

    override suspend fun save(state: GameState) {
        savedState = state
    }
}
