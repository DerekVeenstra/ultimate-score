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
 * Exercises ScoreViewModel's preset actions (add/rename/recolour/delete, PLAN.md section 13)
 * against an in-memory fake [TeamPresetStore] — no Android framework or real DataStore involved,
 * same approach as ScoreViewModelPersistenceTest for [ScoreHistoryStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModelPresetTest {

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

    private fun idSequence(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    @Test
    fun `both preset lists start empty with no store`() {
        val vm = ScoreViewModel()
        assertEquals(TeamPresetLists(), vm.presets.value)
    }

    @Test
    fun `a persisted preset list loads into state and becomes available immediately`() {
        val store = FakeTeamPresetStore(
            initial = TeamPresetLists(
                myTeams = listOf(TeamPreset("1", "Flaming Nipples", TeamColor.PINK)),
                opponents = listOf(TeamPreset("2", "Sockeye", TeamColor.BLUE)),
            ),
        )
        val vm = ScoreViewModel(presetStore = store)

        assertEquals("Flaming Nipples", vm.presets.value.myTeams.single().name)
        assertEquals("Sockeye", vm.presets.value.opponents.single().name)
    }

    @Test
    fun `adding a preset updates state, persists, and returns the created preset`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())

        val created = vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Nipples", TeamColor.PINK)

        assertEquals(TeamPreset("id-0", "Flaming Nipples", TeamColor.PINK), created)
        assertEquals(listOf(created), vm.presets.value.myTeams)
        assertTrue(vm.presets.value.opponents.isEmpty())
        assertEquals(listOf(created), store.saved.getValue(PresetGroup.MY_TEAMS))
    }

    @Test
    fun `the two lists are independent`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())

        vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Nipples", TeamColor.PINK)
        vm.addPreset(PresetGroup.OPPONENTS, "Sockeye", TeamColor.BLUE)

        assertEquals(1, vm.presets.value.myTeams.size)
        assertEquals(1, vm.presets.value.opponents.size)
        assertEquals("Flaming Nipples", vm.presets.value.myTeams.single().name)
        assertEquals("Sockeye", vm.presets.value.opponents.single().name)
    }

    @Test
    fun `a name is sanitized on the way in`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())

        val created = vm.addPreset(PresetGroup.MY_TEAMS, "  Sockeye, F.C.  ", TeamColor.NONE)

        assertEquals("Sockeye, F.C.", created.name)
    }

    @Test
    fun `renaming a preset updates state and persists, leaving its id and colour alone`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())
        val original = vm.addPreset(PresetGroup.OPPONENTS, "Sockeye", TeamColor.BLUE)

        vm.renamePreset(PresetGroup.OPPONENTS, original.id, "Sockeye United")

        val renamed = vm.presets.value.opponents.single()
        assertEquals(original.id, renamed.id)
        assertEquals("Sockeye United", renamed.name)
        assertEquals(TeamColor.BLUE, renamed.color)
        assertEquals(listOf(renamed), store.saved.getValue(PresetGroup.OPPONENTS))
    }

    @Test
    fun `renaming to a blank name is a no-op`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())
        val original = vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Nipples", TeamColor.PINK)

        vm.renamePreset(PresetGroup.MY_TEAMS, original.id, "   ")

        assertEquals(original, vm.presets.value.myTeams.single())
    }

    @Test
    fun `recolouring a preset updates state and persists, leaving its id and name alone`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())
        val original = vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Nipples", TeamColor.PINK)

        vm.recolorPreset(PresetGroup.MY_TEAMS, original.id, TeamColor.GRAY)

        val recolored = vm.presets.value.myTeams.single()
        assertEquals(original.id, recolored.id)
        assertEquals("Flaming Nipples", recolored.name)
        assertEquals(TeamColor.GRAY, recolored.color)
        assertEquals(listOf(recolored), store.saved.getValue(PresetGroup.MY_TEAMS))
    }

    @Test
    fun `deleting a preset removes it from state and persists the shrunk list`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())
        val keep = vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Nipples", TeamColor.PINK)
        val remove = vm.addPreset(PresetGroup.MY_TEAMS, "Flaming Throws", TeamColor.GRAY)

        vm.deletePreset(PresetGroup.MY_TEAMS, remove.id)

        assertEquals(listOf(keep), vm.presets.value.myTeams)
        assertEquals(listOf(keep), store.saved.getValue(PresetGroup.MY_TEAMS))
    }

    @Test
    fun `deleting an unknown id is a no-op`() {
        val store = FakeTeamPresetStore()
        val vm = ScoreViewModel(presetStore = store, presetIdGenerator = idSequence())
        val kept = vm.addPreset(PresetGroup.OPPONENTS, "Sockeye", TeamColor.BLUE)

        vm.deletePreset(PresetGroup.OPPONENTS, "not-a-real-id")

        assertEquals(listOf(kept), vm.presets.value.opponents)
    }

    @Test
    fun `id generation is delegated to the injected generator, not the wall clock, in tests`() {
        val vm = ScoreViewModel(presetIdGenerator = idSequence())
        val first = vm.addPreset(PresetGroup.MY_TEAMS, "A", TeamColor.NONE)
        val second = vm.addPreset(PresetGroup.MY_TEAMS, "B", TeamColor.NONE)
        assertEquals("id-0", first.id)
        assertEquals("id-1", second.id)
    }
}

private class FakeTeamPresetStore(
    initial: TeamPresetLists = TeamPresetLists(),
) : TeamPresetStore {
    private var current = initial

    /** What's been saved per group, for assertions — mirrors FakeScoreHistoryStore's `saved`. */
    val saved: Map<PresetGroup, List<TeamPreset>>
        get() = mapOf(PresetGroup.MY_TEAMS to current.myTeams, PresetGroup.OPPONENTS to current.opponents)

    override suspend fun loadPresets(): TeamPresetLists = current

    override suspend fun savePresets(group: PresetGroup, presets: List<TeamPreset>) {
        current = current.withGroup(group, presets)
    }
}
