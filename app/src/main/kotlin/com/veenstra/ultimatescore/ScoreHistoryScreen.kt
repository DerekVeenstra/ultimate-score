package com.veenstra.ultimatescore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Completed games archived via the score card's "Done" control (see ScoreScreen's
 * `EndGameConfirmScreen`). Its own screen — reached from a "Score history" row on the new-game
 * screen — rather than a section embedded there, since it's read-only browsing with nothing to do
 * with starting a game, and a growing list of saved games would otherwise push the Start/Cancel
 * buttons further away every game played.
 *
 * Deletion is modeled the same way [NewGameSetupScreen]'s own sub-screens are: a private
 * `confirmingDelete` piece of Compose state that swaps in [ConfirmDeleteSavedGameScreen] in place
 * of the list, rather than a second boolean flag one level up.
 */
@Composable
fun ScoreHistoryScreen(
    savedGames: List<SavedGame>,
    onDeleteSavedGame: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf<SavedGame?>(null) }
    val deleting = confirmingDelete

    if (deleting != null) {
        ConfirmDeleteSavedGameScreen(
            game = deleting,
            onDelete = {
                onDeleteSavedGame(deleting.id)
                confirmingDelete = null
            },
            onCancel = { confirmingDelete = null },
        )
    } else {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Score history",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

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
                    SavedGameRow(game = game, onLongClick = { confirmingDelete = game })
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth(0.85f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Back", fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * Reached by long-pressing a saved-game row: delete that one completed game, or cancel. A
 * separate confirm step rather than deleting on the long-press itself — the long-press only
 * *starts* the deletion, the same as it does for a team preset's `EditingScreen` (which reaches
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
 * One entry of the score-history list: a row per team, each tinted with that team's own colour
 * (see [TeamConfig.color]/`backgroundArgb`) the same way the live score card halves are — so a
 * completed game reads at a glance instead of needing the colour picked apart from plain text —
 * plus a trophy on whichever team won and the timestamp below. Long-press to delete (see
 * [ConfirmDeleteSavedGameScreen]) — there's nothing to tap it *for* otherwise, so unlike
 * `SelectableRow` this has no `onClick` of its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedGameRow(game: SavedGame, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
    ) {
        SavedGameTeamRow(team = game.usTeam, score = game.usScore, won = game.usScore > game.themScore)
        SavedGameTeamRow(team = game.themTeam, score = game.themScore, won = game.themScore > game.usScore)
        Text(
            text = formatSavedGameTimestamp(game.completedAtMillis),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f))
                .padding(vertical = 2.dp),
        )
    }
}

/**
 * One team's half of a [SavedGameRow]: name on the left against that team's own background
 * colour, final score clearly set off on the right (with a trophy alongside it when [won] is
 * true) — mirrors ScoreScreen's live halves rather than inventing a new colour treatment for
 * history.
 */
@Composable
private fun SavedGameTeamRow(team: TeamConfig, score: Int, won: Boolean) {
    val background = Color(team.color.backgroundArgb)
    val textColor = Color(textColorArgbFor(team.color.backgroundArgb))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = team.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.7f),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (won) {
                Text(text = "🏆", fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
            }
            Text(
                text = score.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }
    }
}
