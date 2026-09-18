package com.veenstra.ultimatescore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

/**
 * A few small offsets to cycle through on each ambient tick so no pixel stays lit continuously.
 * See PLAN.md section 3 "Ambient".
 */
val BURN_IN_OFFSETS: List<Pair<Dp, Dp>> = listOf(
    0.dp to 0.dp,
    4.dp to 0.dp,
    0.dp to 4.dp,
    (-4).dp to (-4).dp,
)

/** Dim light gray rather than pure white — less power, less burn-in risk, still legible on black. */
private val AmbientTextColor = Color(0xFFB0B0B0)

/**
 * Rendered while the watch is in ambient (always-on, low-power) mode. Black background, thin
 * light-weight numerals only — no filled arcs, no undo control, nothing animates. See PLAN.md
 * section 3 "Ambient".
 */
@Composable
fun AmbientScoreScreen(state: GameState, offsetX: Dp, offsetY: Dp) {
    val usLine = "${state.usTeam.name.uppercase()} ${state.us}"
    val themLine = "${state.themTeam.name.uppercase()} ${state.them}"

    // Team names are user-chosen, so these lines are no longer always as short as "US 8". Shrink
    // for a long one rather than letting it run off the edge of a round screen. Note the
    // background stays pure black whatever the teams' colours are — ambient is about burn-in and
    // battery (PLAN.md section 3), which is exactly what a tinted background would work against.
    val fontSize = when (maxOf(usLine.length, themLine.length)) {
        in 0..10 -> 22.sp
        in 11..16 -> 17.sp
        else -> 14.sp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .offset(x = offsetX, y = offsetY),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = usLine,
                fontSize = fontSize,
                fontWeight = FontWeight.Light,
                color = AmbientTextColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                text = themLine,
                fontSize = fontSize,
                fontWeight = FontWeight.Light,
                color = AmbientTextColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            // ABBA majority gender for the current point, when tracking is on. Kept to a bare
            // letter — ambient is thin light glyphs only, no chrome (PLAN.md section 3).
            state.currentGender?.let { gender ->
                Text(
                    text = "${gender.code}${genderRoundForPoint(state.currentPoint)}",
                    fontSize = (fontSize.value * 0.7f).sp,
                    fontWeight = FontWeight.Light,
                    color = AmbientTextColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
