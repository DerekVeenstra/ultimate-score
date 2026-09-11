package com.veenstra.ultimatescore

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch

/** How long a press must be held before a point commits. See PLAN.md section 2. */
private const val HOLD_DURATION_MS = 400
private const val RELEASE_ANIMATION_MS = 150

/** The screen height sizes in ScoreScreen() were tuned against — see its comment. */
private val REFERENCE_HEIGHT_DP = 227.dp

/**
 * The app's one accent color — used for the hold-arc and the launcher icon (see
 * ic_launcher_foreground.xml) so the two read as the same app. Deliberately not the Wear
 * Compose theme's default primary: a fixed, saturated color reads more reliably than a themed
 * one in direct sunlight, and it never changes across system theme updates.
 */
val AccentColor = Color(0xFF00E5A0)

@Composable
private fun rememberScoreViewModel(): ScoreViewModel {
    val appContext = LocalContext.current.applicationContext
    return viewModel(
        factory = viewModelFactory {
            initializer {
                // One DataStoreScoreRepository instance implements all three stores (PLAN.md
                // section 13, and the "Done" score-history feature) — same underlying
                // `game_state` preferences file, just different keys.
                val repository = DataStoreScoreRepository(appContext)
                ScoreViewModel(
                    historyStore = repository,
                    presetStore = repository,
                    savedGameStore = repository,
                )
            }
        },
    )
}

@Composable
fun WearApp(viewModel: ScoreViewModel = rememberScoreViewModel()) {
    val ambientModeManager = rememberAmbientModeManager()
    CompositionLocalProvider(LocalAmbientModeManager provides ambientModeManager) {
        val ambientMode = LocalAmbientModeManager.current?.currentAmbientMode
        val isReady by viewModel.isReady.collectAsState()

        when {
            !isReady -> {
                // Avoid flashing 0-0 over a game that's actually in progress on disk while the
                // persisted history is still loading. See PLAN.md section 4 "Persistence".
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }

            ambientMode is AmbientMode.Ambient -> {
                val state by viewModel.state.collectAsState()
                var offsetIndex by remember { mutableStateOf(0) }
                LaunchedEffect(ambientModeManager) {
                    ambientModeManager.withAmbientTick {
                        offsetIndex = (offsetIndex + 1) % BURN_IN_OFFSETS.size
                    }
                }
                val (offsetX, offsetY) = BURN_IN_OFFSETS[offsetIndex]
                AmbientScoreScreen(state = state, offsetX = offsetX, offsetY = offsetY)
            }

            else -> {
                val state by viewModel.state.collectAsState()
                var showNewGameSetup by remember { mutableStateOf(false) }
                var showEndGameConfirm by remember { mutableStateOf(false) }

                MaterialTheme {
                    when {
                        showNewGameSetup -> {
                            val presets by viewModel.presets.collectAsState()
                            val savedGames by viewModel.savedGames.collectAsState()
                            NewGameSetupScreen(
                                currentState = state,
                                presets = presets,
                                savedGames = savedGames,
                                onStart = { us, them, abbaStart ->
                                    viewModel.newGame(us, them, abbaStart)
                                    showNewGameSetup = false
                                },
                                onCancel = { showNewGameSetup = false },
                                onAddPreset = viewModel::addPreset,
                                onRenamePreset = viewModel::renamePreset,
                                onRecolorPreset = viewModel::recolorPreset,
                                onDeletePreset = viewModel::deletePreset,
                                onDeleteSavedGame = viewModel::deleteSavedGame,
                            )
                        }

                        showEndGameConfirm -> {
                            EndGameConfirmScreen(
                                state = state,
                                onConfirm = {
                                    viewModel.completeGame()
                                    showEndGameConfirm = false
                                    // "Confirming Done brings the user back to the new game
                                    // screen" — the just-finished teams stay pre-selected there
                                    // via the same stickiness matching a recurring matchup
                                    // already relies on (see ScoreViewModel.completeGame).
                                    showNewGameSetup = true
                                },
                                onCancel = { showEndGameConfirm = false },
                            )
                        }

                        else -> {
                            ScoreScreen(
                                state = state,
                                onScore = viewModel::score,
                                onUndo = viewModel::undo,
                                onRequestNewGame = { showNewGameSetup = true },
                                onRequestEndGame = { showEndGameConfirm = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreScreen(
    state: GameState,
    onScore: (Team) -> Unit,
    onUndo: () -> Unit,
    onRequestNewGame: () -> Unit,
    onRequestEndGame: () -> Unit,
) {
    // Sizes below were tuned by eye on a 454px/320dpi round screen (227dp tall). A 360px
    // square AVD at the SAME density is only 180dp tall — 21% less room — and hardcoding those
    // sizes clipped "THEM" and undo off the bottom edge there (found in Phase 6's square-AVD
    // check). So every size that affects vertical layout scales with the actual available
    // height instead, which is what "any Android smart watch" (PLAN.md section 1) requires.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val heightRatio = (maxHeight / REFERENCE_HEIGHT_DP).coerceIn(0.6f, 1.2f)
        val footerHeight = (44 * heightRatio).coerceAtLeast(32f).dp
        val dividerHeight = 1.dp

        // The US/THEM divider must sit at the exact vertical center of the screen — a user
        // playing on a real TicWatch caught this being off-center originally. Both zones get
        // the exact same outer height (so the divider is centered and both are equally-sized
        // tap targets), and the undo control overlays the bottom edge instead of occupying its
        // own row (matches PLAN.md section 3's mockup, where undo sits *within* the bottom half).
        val halfHeight = (maxHeight - dividerHeight) / 2

        // But undo's overlay still needs a strip of THEM's zone clear for it to sit in without
        // colliding with "THEM" — simply centering THEM's content in the full halfHeight (as a
        // first attempt at this fix did) let the label drift down into where undo overlays,
        // which a user playing on the real watch also caught. So THEM's content is confined to
        // a sub-area that explicitly excludes the footer's strip and top-aligned within its
        // zone (i.e., it starts right after the divider) — overlap becomes geometrically
        // impossible rather than something several rounds of arithmetic have to get exactly
        // right. US's content area is simply its whole zone (nothing overlays US, so no
        // exclusion is needed there), which also fixes sizing: both zones size their text off
        // the *smaller* of the two content areas, so neither one clips.
        val usContentHeight = halfHeight
        val themContentHeight = (halfHeight - footerHeight).coerceAtLeast(40.dp)
        val contentHeight = minOf(usContentHeight, themContentHeight)
        val numeralSize = (contentHeight.value * 0.56f).coerceAtLeast(24f).sp

        // Team names are user-chosen and variable-length now, so the label has to shrink for a
        // long one ("FLAMING NIPPLES") where "US" had room to spare. Sized off the longer of the
        // two names so both halves keep matching label text.
        val longestName = maxOf(state.usTeam.name.length, state.themTeam.name.length)
        val labelSize = (contentHeight.value * 0.20f)
            .coerceAtLeast(10f)
            .let { if (longestName > 8) it * 0.85f else it }
            .coerceAtLeast(10f)
            .sp

        // True-colour backgrounds (PLAN.md section 11 "True colours") can be light enough — GRAY,
        // ORANGE — that white text would fail contrast outright, so each zone's text colour is
        // computed from its own background rather than assumed to be white.
        val usBackground = state.usTeam.color.backgroundArgb
        val themBackground = state.themTeam.color.backgroundArgb
        val usTextColor = Color(textColorArgbFor(usBackground))
        val themTextColor = Color(textColorArgbFor(themBackground))

        Column(modifier = Modifier.fillMaxSize()) {
            HoldToScoreZone(
                team = Team.US,
                label = state.usTeam.name,
                score = state.us,
                labelOnTop = true,
                arc = ArcEdge.Top,
                numeralSize = numeralSize,
                labelSize = labelSize,
                contentAreaHeight = usContentHeight,
                background = Color(usBackground),
                textColor = usTextColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(halfHeight),
                onScored = { onScore(Team.US) },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dividerHeight)
                    .background(Color.White.copy(alpha = 0.2f)),
            )

            HoldToScoreZone(
                team = Team.THEM,
                label = state.themTeam.name,
                score = state.them,
                labelOnTop = false,
                arc = ArcEdge.Bottom,
                numeralSize = numeralSize,
                labelSize = labelSize,
                contentAreaHeight = themContentHeight,
                background = Color(themBackground),
                textColor = themTextColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(halfHeight),
                onScored = { onScore(Team.THEM) },
            )
        }

        UndoControl(
            canUndo = state.canUndo,
            onUndo = onUndo,
            onRequestNewGame = onRequestNewGame,
            iconColor = themTextColor,
            iconSize = (20 * heightRatio).coerceAtLeast(16f).sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(footerHeight),
        )

        // The majority gender for the point being played now (ABBA rule) — a small pill on the
        // divider's left edge so it reads as belonging to neither side. Only present when a
        // starting gender was chosen in setup; otherwise the card looks exactly as it did before.
        state.currentGender?.let { gender ->
            GenderBadge(
                gender = gender,
                fontSize = (13 * heightRatio).coerceAtLeast(10f).sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp),
            )
        }

        // "Done" — marks the game over. Mirrors the gender badge's position on the divider's
        // opposite edge. Unlike the badge, this needs its own tap target: `clickable` requires an
        // unconsumed down event the same way UndoControl's overlay does, so a hold that starts
        // here is captured here rather than falling through to the HoldToScoreZone underneath.
        DoneButton(
            onClick = onRequestEndGame,
            fontSize = (13 * heightRatio).coerceAtLeast(10f).sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
        )
    }
}

/**
 * The ABBA majority-gender indicator for the current point. Drawn last in [ScoreScreen] so it
 * sits on top of the divider; it has no gesture handler of its own, so a hold started on the pill
 * still falls through to whichever half-screen zone is underneath.
 */
@Composable
private fun GenderBadge(gender: Gender, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = gender.name,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}

/**
 * Marks the game over. Tapping this alone does *not* end the game — it opens
 * [EndGameConfirmScreen], so a stray tap here can't lose the score; see PLAN.md's "Done" feature.
 * Styled in the accent colour (unlike the merely-informational [GenderBadge]) since this is an
 * action, not a status indicator.
 */
@Composable
private fun DoneButton(onClick: () -> Unit, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(AccentColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Done",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}

/**
 * Reached by tapping [DoneButton] — the "validate the press was intentional" step (PLAN.md's
 * "Done" feature). Shows the final score so ending the wrong game is caught before it's archived;
 * confirming calls back into [ScoreViewModel.completeGame] (via WearApp) and cancelling returns
 * to the score card unchanged. Mirrors the button styling of NewGameSetupScreen's own confirm
 * screens (accent "primary action" pill, plain "Cancel" pill below it).
 */
@Composable
private fun EndGameConfirmScreen(state: GameState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "End game?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${state.usTeam.name.uppercase()} ${state.us} – ${state.them} " +
                    state.themTeam.name.uppercase(),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(0.85f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AccentColor)
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "End Game", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
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

private enum class ArcEdge { Top, Bottom }

/**
 * A press-and-hold target. Holding fills a progress arc around that edge of the screen; letting
 * go before it completes cancels with no change. Completing it fires a haptic and reports one
 * point scored. See PLAN.md section 3 "Why hold-to-score instead of tap-and-confirm".
 */
@Composable
private fun HoldToScoreZone(
    team: Team,
    label: String,
    score: Int,
    labelOnTop: Boolean,
    arc: ArcEdge,
    numeralSize: TextUnit,
    labelSize: TextUnit,
    /**
     * The content (numeral + label) is confined and top-aligned within this height, rather than
     * centered across the zone's full [modifier] height — so it can never grow down into
     * whatever's overlaid on the zone's lower edge (the undo control, for THEM). For a zone
     * nothing overlays (US), this is just the zone's full height, which makes top-aligning a
     * same-size sub-box behave identically to centering in the full zone — see ScoreScreen().
     */
    contentAreaHeight: Dp,
    /** This team's half-screen background — its true colour, or black for no colour. */
    background: Color,
    /**
     * Whichever of black/white best contrasts with [background] (see [textColorArgbFor]) — a
     * true-colour background can be light enough that white numerals would fail contrast.
     */
    textColor: Color,
    modifier: Modifier = Modifier,
    onScored: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val progress = remember(team) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .background(background)
            .pointerInput(team) {
                detectTapGestures(
                    onPress = {
                        val holdJob = scope.launch {
                            progress.animateTo(1f, tween(HOLD_DURATION_MS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onScored()
                        }
                        tryAwaitRelease()
                        holdJob.cancel()
                        scope.launch {
                            progress.animateTo(0f, tween(RELEASE_ANIMATION_MS))
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        ScoreHoldArc(progress = progress.value, edge = arc, modifier = Modifier.fillMaxSize())

        val numeral = @Composable {
            Text(
                text = score.toString(),
                fontSize = numeralSize,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
            )
        }
        val teamLabel = @Composable {
            Text(
                text = label.uppercase(),
                fontSize = labelSize,
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(contentAreaHeight)
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (labelOnTop) {
                    teamLabel()
                    numeral()
                } else {
                    numeral()
                    teamLabel()
                }
            }
        }
    }
}

/** Draws the progress ring described in PLAN.md section 3 along the given edge of the screen. */
@Composable
private fun ScoreHoldArc(progress: Float, edge: ArcEdge, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Canvas(modifier = modifier) {
        val strokeWidthPx = 6.dp.toPx()
        val radius = (size.minDimension / 2f) - (strokeWidthPx / 2f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        // Angle convention: 0deg = 3 o'clock, increasing clockwise. Top edge sweeps
        // 9 o'clock -> 12 -> 3 o'clock; bottom edge sweeps 3 o'clock -> 6 -> 9 o'clock.
        val startAngle = if (edge == ArcEdge.Top) 180f else 0f
        drawArc(
            color = AccentColor,
            startAngle = startAngle,
            sweepAngle = 180f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx),
        )
    }
}

/**
 * Tap to undo the last point (a no-op when there is none to undo). Long-press to ask about
 * starting a new game. Dimmed when there is nothing to undo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UndoControl(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onRequestNewGame: () -> Unit,
    iconSize: TextUnit,
    /** Overlays THEM's background (see ScoreScreen()), so its colour comes from that team. */
    iconColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .combinedClickable(
                onClick = onUndo,
                onLongClick = onRequestNewGame,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "↺",
            fontSize = iconSize,
            color = iconColor.copy(alpha = if (canUndo) 0.8f else 0.3f),
        )
    }
}
