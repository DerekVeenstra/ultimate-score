package com.veenstra.ultimatescore

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper

/** Key the typed text comes back under from the Wear text-input activity — see [rememberTextInputLauncher]. */
private const val TEXT_INPUT_KEY = "typed_text"

/**
 * The setup flow is three screens modeled as one piece of Compose state within this file, rather
 * than a second Activity or nav graph (PLAN.md section 13) — small enough on a one-screen-at-a-
 * time watch app that a nav graph would be pure overhead. [Picking] is the normal two-list view;
 * tapping "+ New team..." moves to [ChoosingColor] once a name has been typed, and long-pressing
 * an existing preset moves to [Editing].
 */
private sealed interface SetupMode {
    data object Picking : SetupMode
    data class ChoosingColor(val group: PresetGroup, val name: String) : SetupMode
    data class Editing(val group: PresetGroup, val preset: TeamPreset) : SetupMode
    data class ConfirmingDeleteSavedGame(val game: SavedGame) : SetupMode
}

/**
 * Starting a new game: pick each side's saved team (or nothing), then start. Replaces the old
 * plain "New game?" confirm — the score-loss warning is inline here instead, so starting a game
 * is still the same number of taps as before if you don't pick anything (PLAN.md sections 11
 * and 13).
 *
 * Both sides are optional: leave them alone and you get the plain US/THEM, no-colour game the app
 * had before either feature existed. There is deliberately no free-typed name on either side any
 * more (section 13 superseded that "presets for US, free text for the opponent" split from
 * section 11) — a name now only exists as a saved, reusable [TeamPreset], created inline from the
 * "+ New team..." row at the end of each list and managed (renamed/recoloured/deleted) by long-
 * pressing it.
 */
@Composable
fun NewGameSetupScreen(
    currentState: GameState,
    presets: TeamPresetLists,
    savedGames: List<SavedGame>,
    onStart: (us: TeamConfig, them: TeamConfig, abbaStart: Gender?) -> Unit,
    onCancel: () -> Unit,
    onAddPreset: (group: PresetGroup, name: String, color: TeamColor) -> TeamPreset,
    onRenamePreset: (group: PresetGroup, id: String, newName: String) -> Unit,
    onRecolorPreset: (group: PresetGroup, id: String, color: TeamColor) -> Unit,
    onDeletePreset: (group: PresetGroup, id: String) -> Unit,
    onDeleteSavedGame: (id: String) -> Unit,
) {
    var mode by remember { mutableStateOf<SetupMode>(SetupMode.Picking) }

    // Selection is tracked by preset id, not by copying a TeamConfig into local state the way the
    // pre-preset version did. That old `usTeam.name == preset.name` comparison (see PLAN.md
    // section 11) couldn't tell two same-named presets apart and broke the moment a preset was
    // renamed; an id can't. Pre-populated by matching the current game's team against the saved
    // presets so a recurring team stays selected across setup visits, the same stickiness section
    // 11 had — but if nothing matches (a fresh install, or a game still in progress from before
    // this feature existed with a free-typed opponent name that has no corresponding preset),
    // nothing is selected, which is exactly the "nothing picked" v1 look section 13 requires to
    // keep working.
    var usSelectedId by remember {
        mutableStateOf(presets.myTeams.find { it.toConfig() == currentState.usTeam }?.id)
    }
    var themSelectedId by remember {
        mutableStateOf(presets.opponents.find { it.toConfig() == currentState.themTeam }?.id)
    }

    val usTeam = presets.myTeams.find { it.id == usSelectedId }?.toConfig() ?: TeamConfig.DEFAULT_US
    val themTeam = presets.opponents.find { it.id == themSelectedId }?.toConfig() ?: TeamConfig.DEFAULT_THEM

    // ABBA starting gender for the new game — `null` means "don't track it". Pre-populated from
    // the game currently in progress so re-opening setup mid-game keeps the choice visible.
    var abbaStart by remember { mutableStateOf(currentState.abbaStart) }

    val newMyTeamNameLauncher = rememberTextInputLauncher(label = "Team name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = SetupMode.ChoosingColor(PresetGroup.MY_TEAMS, trimmed)
    }
    val newOpponentNameLauncher = rememberTextInputLauncher(label = "Opponent name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = SetupMode.ChoosingColor(PresetGroup.OPPONENTS, trimmed)
    }
    // Reused for renaming either side's preset — which one is being renamed is read off `mode`
    // itself when the result comes back (this is only ever invoked while `mode` is `Editing`), so
    // one launcher instance covers both, per PLAN.md section 13's "factor the RemoteInput launcher
    // into one reusable helper" guidance rather than a third near-identical copy.
    val renameLauncher = rememberTextInputLauncher(label = "Team name") { typed ->
        val editing = mode as? SetupMode.Editing ?: return@rememberTextInputLauncher
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRenamePreset(editing.group, editing.preset.id, trimmed)
    }

    when (val current = mode) {
        is SetupMode.Picking -> PickingScreen(
            currentState = currentState,
            presets = presets,
            savedGames = savedGames,
            usSelectedId = usSelectedId,
            themSelectedId = themSelectedId,
            onSelectUs = { id -> usSelectedId = if (usSelectedId == id) null else id },
            onSelectThem = { id -> themSelectedId = if (themSelectedId == id) null else id },
            onLongPress = { group, preset -> mode = SetupMode.Editing(group, preset) },
            onLongPressSavedGame = { game -> mode = SetupMode.ConfirmingDeleteSavedGame(game) },
            onAddMyTeam = newMyTeamNameLauncher,
            onAddOpponent = newOpponentNameLauncher,
            abbaStart = abbaStart,
            onAbbaStartChange = { abbaStart = it },
            onStart = { onStart(usTeam, themTeam, abbaStart) },
            onCancel = onCancel,
        )

        is SetupMode.ChoosingColor -> ChoosingColorScreen(
            name = current.name,
            onSave = { color ->
                val preset = onAddPreset(current.group, current.name, color)
                when (current.group) {
                    PresetGroup.MY_TEAMS -> usSelectedId = preset.id
                    PresetGroup.OPPONENTS -> themSelectedId = preset.id
                }
                mode = SetupMode.Picking
            },
            onCancel = { mode = SetupMode.Picking },
        )

        is SetupMode.Editing -> {
            // Re-read the live preset every recomposition rather than trusting the snapshot
            // captured when this screen was entered, so a recolour shows up immediately in this
            // same screen's swatch highlight.
            val live = presets.forGroup(current.group).find { it.id == current.preset.id }
            if (live != null) {
                EditingScreen(
                    preset = live,
                    onRename = renameLauncher,
                    onRecolor = { color -> onRecolorPreset(current.group, live.id, color) },
                    onDelete = {
                        onDeletePreset(current.group, live.id)
                        // The "deleting the selected preset must fall back to the default" rule
                        // (PLAN.md section 13) lives here rather than in the ViewModel, because
                        // the selection itself is this screen's Compose state, not shared state.
                        when (current.group) {
                            PresetGroup.MY_TEAMS -> if (usSelectedId == live.id) usSelectedId = null
                            PresetGroup.OPPONENTS -> if (themSelectedId == live.id) themSelectedId = null
                        }
                        mode = SetupMode.Picking
                    },
                    onDone = { mode = SetupMode.Picking },
                )
            } else {
                // Only reachable if the preset vanished out from under this screen (not possible
                // on a single-user watch app today, but falling back beats rendering a dangling
                // reference). LaunchedEffect rather than assigning `mode` directly in the
                // composable body, which would be a state mutation during composition.
                LaunchedEffect(Unit) { mode = SetupMode.Picking }
            }
        }

        is SetupMode.ConfirmingDeleteSavedGame -> ConfirmDeleteSavedGameScreen(
            game = current.game,
            onDelete = {
                onDeleteSavedGame(current.game.id)
                mode = SetupMode.Picking
            },
            onCancel = { mode = SetupMode.Picking },
        )
    }
}

/** The normal two-list view: your team, the opponent, Start/Cancel. */
@Composable
private fun PickingScreen(
    currentState: GameState,
    presets: TeamPresetLists,
    savedGames: List<SavedGame>,
    usSelectedId: String?,
    themSelectedId: String?,
    onSelectUs: (String) -> Unit,
    onSelectThem: (String) -> Unit,
    onLongPress: (PresetGroup, TeamPreset) -> Unit,
    onLongPressSavedGame: (SavedGame) -> Unit,
    onAddMyTeam: () -> Unit,
    onAddOpponent: () -> Unit,
    abbaStart: Gender?,
    onAbbaStartChange: (Gender?) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                text = "New game",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        if (currentState.canUndo) {
            item {
                Text(
                    text = "Current score ${currentState.us}–${currentState.them} will be lost.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        item { SectionHeader("Your team") }

        items(presets.myTeams.size) { index ->
            val preset = presets.myTeams[index]
            SelectableRow(
                label = preset.name,
                selected = usSelectedId == preset.id,
                swatch = preset.color,
                onClick = { onSelectUs(preset.id) },
                onLongClick = { onLongPress(PresetGroup.MY_TEAMS, preset) },
            )
        }

        item {
            SelectableRow(
                label = "+ New team…",
                selected = false,
                swatch = null,
                onClick = onAddMyTeam,
            )
        }

        item { SectionHeader("Opponent") }

        items(presets.opponents.size) { index ->
            val preset = presets.opponents[index]
            SelectableRow(
                label = preset.name,
                selected = themSelectedId == preset.id,
                swatch = preset.color,
                onClick = { onSelectThem(preset.id) },
                onLongClick = { onLongPress(PresetGroup.OPPONENTS, preset) },
            )
        }

        item {
            SelectableRow(
                label = "+ New team…",
                selected = false,
                swatch = null,
                onClick = onAddOpponent,
            )
        }

        item { SectionHeader("Gender ratio · ABBA") }

        item { AbbaStartSelector(selected = abbaStart, onSelect = onAbbaStartChange) }

        item {
            Text(
                text = if (abbaStart == null) {
                    "Off"
                } else {
                    "Point 1 is majority ${if (abbaStart == Gender.M) "men" else "women"}"
                },
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(0.85f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AccentColor)
                    .clickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Start game",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Cancel", fontSize = 14.sp)
            }
        }

        // Score history — completed games archived via the score card's "Done" control. Placed
        // after Start/Cancel rather than above them so picking teams and starting a quick game
        // (the common path) stays exactly as many scrolls away as it already was; history is
        // informational, read by continuing to scroll rather than something in the way of it.
        item { SectionHeader("Score history") }

        if (savedGames.isEmpty()) {
            item {
                Text(
                    text = "No saved games yet.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 4.dp),
                )
            }
        } else {
            val sorted = savedGames.sortedByDescending { it.completedAtMillis }
            items(sorted.size) { index ->
                val game = sorted[index]
                SavedGameRow(game = game, onLongClick = { onLongPressSavedGame(game) })
            }
        }
    }
}

/**
 * Step two of inline creation (PLAN.md section 13): the name is already typed, now pick a colour
 * (or leave it [TeamColor.NONE]) and save. Reuses [ColorSwatches] rather than a bespoke picker.
 */
@Composable
private fun ChoosingColorScreen(name: String, onSave: (TeamColor) -> Unit, onCancel: () -> Unit) {
    var color by remember { mutableStateOf(TeamColor.NONE) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                text = "Colour for “$name”",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        item { ColorSwatches(selected = color, onSelect = { color = it }) }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(0.85f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AccentColor)
                    .clickable { onSave(color) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Save", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Cancel", fontSize = 14.sp)
            }
        }
    }
}

/**
 * Reached by long-pressing a saved preset (PLAN.md section 13, decision 4): rename, recolour, or
 * delete that one preset. Recolouring applies immediately (there's nothing to "save" — the swatch
 * tap itself is the whole gesture, same as it always was in [ColorSwatches]); renaming goes
 * through the shared RemoteInput launcher; deleting returns to the picking screen.
 */
@Composable
private fun EditingScreen(
    preset: TeamPreset,
    onRename: () -> Unit,
    onRecolor: (TeamColor) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                text = "Edit team",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        item {
            SelectableRow(
                label = preset.name,
                selected = false,
                swatch = preset.color,
                trailing = "Rename",
                onClick = onRename,
            )
        }

        item { ColorSwatches(selected = preset.color, onSelect = onRecolor) }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFB00020))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Delete", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Done", fontSize = 14.sp)
            }
        }
    }
}

/**
 * Reached by long-pressing a saved-game row: delete that one completed game, or cancel. A
 * separate confirm step rather than deleting on the long-press itself — the long-press only
 * *starts* the deletion, the same as it does for a team preset's [EditingScreen] (which reaches
 * its own destructive Delete button, not an instant delete) — since removing history is
 * permanent and there's nothing else to undo it with, unlike undoing a point.
 */
@Composable
private fun ConfirmDeleteSavedGameScreen(game: SavedGame, onDelete: () -> Unit, onCancel: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                text = "Delete this game?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        item {
            Text(
                text = "${game.usTeam.name} ${game.usScore} – ${game.themScore} ${game.themTeam.name}",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 4.dp),
            )
        }

        item {
            Text(
                text = formatSavedGameTimestamp(game.completedAtMillis),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFB00020))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Delete", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        item {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.85f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Cancel", fontSize = 14.sp)
            }
        }
    }
}

/**
 * One row of the "Score history" section: the matchup, final score, and when it ended. Long-press
 * to delete (see [ConfirmDeleteSavedGameScreen]) — there's nothing to tap it *for* otherwise, so
 * unlike [SelectableRow] this has no `onClick` of its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedGameRow(game: SavedGame, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = "${game.usTeam.name} ${game.usScore} – ${game.themScore} ${game.themTeam.name}",
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSavedGameTimestamp(game.completedAtMillis),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * One reusable wrapper around Wear's standard `RemoteInputIntentHelper` text-entry activity
 * (keyboard *and* voice dictation) — see PLAN.md section 11. This screen now needs it in three
 * places (new my-team, new opponent, rename), so the intent-building/result-parsing boilerplate
 * that used to be duplicated at the single call site section 11 had lives here once instead of
 * being copy-pasted twice more (PLAN.md section 13). [onResult] gets `null` for a cancelled or
 * otherwise non-OK result — every call site here treats that the same as an empty typed string:
 * "nothing usable came back, so do nothing."
 */
@Composable
private fun rememberTextInputLauncher(label: String, onResult: (String?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val typed = if (result.resultCode == Activity.RESULT_OK) {
            RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(TEXT_INPUT_KEY)?.toString()
        } else {
            null
        }
        onResult(typed)
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val inputs = listOf(RemoteInput.Builder(TEXT_INPUT_KEY).setLabel(label).build())
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, inputs)
        launcher.launch(intent)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * One tappable row: a name, an optional colour dot, and an optional trailing hint. Tap always
 * fires [onClick]; when [onLongClick] is supplied the row also responds to a long press (PLAN.md
 * section 13, decision 4) — the two need different Modifier chains ([combinedClickable] only
 * makes sense once there's a second gesture to combine with), so which one is used depends on
 * whether a preset row (rename/recolour/delete via long-press) or a plain action row (the
 * "+ New team..." row, which has nothing to long-press into) is being drawn.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    swatch: TeamColor?,
    trailing: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth(0.9f)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
            )
            .then(
                if (selected) Modifier.border(1.5.dp, AccentColor, RoundedCornerShape(20.dp))
                else Modifier,
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (swatch != null) {
                    // A thin outline regardless of the swatch's own colour -- NONE's near-black
                    // dot (see TeamColor's doc) would otherwise nearly disappear against this
                    // row's own dark background, especially once selected (PLAN.md section 18).
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(swatch.swatchArgb))
                            .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    )
                }
                Text(
                    text = label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (swatch != null) 8.dp else 0.dp),
                )
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    fontSize = 11.sp,
                    color = AccentColor,
                )
            }
        }
    }
}

/**
 * A three-way segmented control — Off / M / F — for the ABBA starting gender (PLAN.md section 19).
 * "Off" is a real option, not a separate switch, because that's the state most pickup games want
 * and it keeps the score card looking exactly as it did before this feature.
 */
@Composable
private fun AbbaStartSelector(selected: Gender?, onSelect: (Gender?) -> Unit) {
    val options: List<Pair<String, Gender?>> =
        listOf("Off" to null, "M" to Gender.M, "F" to Gender.F)
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (label, value) ->
            val isSelected = selected == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(
                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.06f),
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** The pickable palette, two rows of four so each dot stays a comfortable tap target. */
@Composable
private fun ColorSwatches(selected: TeamColor, onSelect: (TeamColor) -> Unit) {
    val all = TeamColor.entries
    Box(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            all.chunked(4).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { color ->
                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable { onSelect(color) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (color == selected) 26.dp else 22.dp)
                                    .clip(CircleShape)
                                    .background(Color(color.swatchArgb))
                                    .then(
                                        if (color == selected) {
                                            Modifier.border(2.dp, Color.White, CircleShape)
                                        } else {
                                            // NONE's near-black swatch would otherwise all but
                                            // vanish against this screen's own black background
                                            // when it isn't the selected one (PLAN.md section 18).
                                            Modifier.border(
                                                0.5.dp,
                                                Color.White.copy(alpha = 0.35f),
                                                CircleShape,
                                            )
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
