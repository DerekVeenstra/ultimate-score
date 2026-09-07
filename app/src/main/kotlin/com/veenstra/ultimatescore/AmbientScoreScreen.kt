package com.veenstra.ultimatescore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .offset(x = offsetX, y = offsetY),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "US ${state.us}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                color = AmbientTextColor,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "THEM ${state.them}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                color = AmbientTextColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
