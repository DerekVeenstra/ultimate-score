package com.veenstra.ultimatescore

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/** Key the opponent-name text comes back under from the Wear text-input activity. */
private const val OPPONENT_NAME_KEY = "opponent_name"

/**
 * Starting a new game: pick each team's name and colour, then start. Replaces the old plain
 * "New game?" confirm — the score-loss warning is inline here instead, so starting a game is
 * still the same number of taps as before if you don't care about names (see PLAN.md section 11).
 *
 * Both sides are optional: leave them alone and you get the plain US/THEM, no-colour game the app
 * had before. Pre-populated with the current game's teams, so a recurring team stays selected.
 */
@Composable
fun NewGameSetupScreen(
    currentState: GameState,
    onStart: (us: TeamConfig, them: TeamConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var usTeam by remember { mutableStateOf(currentState.usTeam) }
    var themTeam by remember { mutableStateOf(currentState.themTeam) }

    val opponentNameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val typed = RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(OPPONENT_NAME_KEY)
                ?.toString()
            themTeam = TeamConfig.named(typed, themTeam.color, TeamConfig.DEFAULT_THEM)
        }
    }

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

        items(TeamConfig.US_PRESETS.size) { index ->
            val preset = TeamConfig.US_PRESETS[index]
            SelectableRow(
                label = preset.name,
                selected = usTeam.name == preset.name,
                swatch = preset.color,
                // Picking a preset adopts its colour too; the swatches below can still override.
                onClick = { usTeam = preset },
            )
        }

        item {
            ColorSwatches(
                selected = usTeam.color,
                onSelect = { usTeam = usTeam.copy(color = it) },
            )
        }

        item { SectionHeader("Opponent") }

        item {
            SelectableRow(
                label = themTeam.name,
                selected = false,
                swatch = null,
                trailing = "Edit",
                onClick = {
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    val inputs = listOf(
                        RemoteInput.Builder(OPPONENT_NAME_KEY)
                            .setLabel("Opponent name")
                            .build(),
                    )
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, inputs)
                    opponentNameLauncher.launch(intent)
                },
            )
        }

        item {
            ColorSwatches(
                selected = themTeam.color,
                onSelect = { themTeam = themTeam.copy(color = it) },
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
                    .clickable { onStart(usTeam, themTeam) },
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

/** One tappable row: a name, an optional colour dot, and an optional trailing hint. */
@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    swatch: TeamColor?,
    trailing: String? = null,
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (swatch != null && swatch != TeamColor.NONE) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(swatch.swatchArgb)),
                    )
                }
                Text(
                    text = label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = if (swatch != null && swatch != TeamColor.NONE) 8.dp else 0.dp,
                    ),
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
                                            Modifier
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
