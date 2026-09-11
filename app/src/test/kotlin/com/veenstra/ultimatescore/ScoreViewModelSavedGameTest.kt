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
 * Exercises ScoreViewModel's "Done" feature — [ScoreViewModel.completeGame] and
 * [ScoreViewModel.deleteSavedGame] — against an in-memory fake [SavedGameStore], no Android
 * framework or real DataStore involved. Same approach as ScoreViewModelPresetTest for
 * [TeamPresetStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModelSavedGameTest {

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
    fun `saved games start empty with no store`() {
        val vm = ScoreViewModel()
        assertTrue(vm.savedGames.value.isEmpty())
    }

    @Test
    fun `a persisted saved-game list loads into state and becomes available immediately`() {
        val store = FakeSavedGameStore(
            initial = listOf(SavedGame("1", TeamConfig.DEFAULT_US, TeamConfig.DEFAULT_THEM, 12, 9, 1000)),
        )
        val vm = ScoreViewModel(savedGameStore = store)

        assertEquals(12, vm.savedGames.value.single().usScore)
    }

    @Test
    fun `completing a game archives its final score and both teams' identities`() {
        val store = FakeSavedGameStore()
        val vm = ScoreViewModel(
            savedGameStore = store,
            savedGameIdGenerator = { "game-1" },
            clock = { 5_000L },
        )
        vm.newGame(
            usTeam = TeamConfig("Flaming Nipples", TeamColor.PINK),
            themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
        )
        vm.score(Team.US)
        vm.score(Team.US)
        vm.score(Team.THEM)

        vm.completeGame()

        val saved = vm.savedGames.value.single()
        assertEquals("game-1", saved.id)
        assertEquals(TeamConfig("Flaming Nipples", TeamColor.PINK), saved.usTeam)
        assertEquals(TeamConfig("Sockeye", TeamColor.BLUE), saved.themTeam)
        assertEquals(2, saved.usScore)
        assertEquals(1, saved.themScore)
        assertEquals(5_000L, saved.completedAtMillis)
        assertEquals(listOf(saved), store.saved)
    }

    @Test
    fun `completing a game clears the live game's history but keeps its teams and ABBA choice`() {
        val vm = ScoreViewModel(savedGameStore = FakeSavedGameStore())
        vm.newGame(
            usTeam = TeamConfig("Flaming Nipples", TeamColor.PINK),
            themTeam = TeamConfig("Sockeye", TeamColor.BLUE),
            abbaStart = Gender.M,
        )
        vm.score(Team.US)

        vm.completeGame()

        assertTrue(vm.state.value.history.isEmpty())
        assertEquals(TeamConfig("Flaming Nipples", TeamColor.PINK), vm.state.value.usTeam)
        assertEquals(TeamConfig("Sockeye", TeamColor.BLUE), vm.state.value.themTeam)
        assertEquals(Gender.M, vm.state.value.abbaStart)
        assertTrue(vm.state.value.canUndo.not())
    }

    @Test
    fun `completing a 0-0 game still archives it`() {
        val store = FakeSavedGameStore()
        val vm = ScoreViewModel(savedGameStore = store, savedGameIdGenerator = { "game-1" })

        vm.completeGame()

        val saved = vm.savedGames.value.single()
        assertEquals(0, saved.usScore)
        assertEquals(0, saved.themScore)
    }

    @Test
    fun `deleting a saved game removes it from state and persists the shrunk list`() {
        val store = FakeSavedGameStore()
        var nextId = 0
        val vm = ScoreViewModel(savedGameStore = store, savedGameIdGenerator = { "game-${nextId++}" })
        vm.completeGame()
        val keep = vm.savedGames.value.single()
        vm.score(Team.US)
        vm.completeGame()
        val remove = vm.savedGames.value.last()

        vm.deleteSavedGame(remove.id)

        assertEquals(listOf(keep), vm.savedGames.value)
        assertEquals(listOf(keep), store.saved)
    }

    @Test
    fun `deleting an unknown id is a no-op`() {
        val store = FakeSavedGameStore()
        val vm = ScoreViewModel(savedGameStore = store, savedGameIdGenerator = { "game-1" })
        vm.completeGame()
        val kept = vm.savedGames.value.single()

        vm.deleteSavedGame("not-a-real-id")

        assertEquals(listOf(kept), vm.savedGames.value)
    }

    @Test
    fun `id generation is delegated to the injected generator, not the wall clock, in tests`() {
        var nextId = 0
        val vm = ScoreViewModel(savedGameIdGenerator = { "game-${nextId++}" })

        vm.completeGame()
        vm.score(Team.US)
        vm.completeGame()

        assertEquals(listOf("game-0", "game-1"), vm.savedGames.value.map { it.id })
    }
}

private class FakeSavedGameStore(initial: List<SavedGame> = emptyList()) : SavedGameStore {
    var saved: List<SavedGame> = initial
        private set

    override suspend fun loadSavedGames(): List<SavedGame> = saved

    override suspend fun saveSavedGames(games: List<SavedGame>) {
        saved = games
    }
}
