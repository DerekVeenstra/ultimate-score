# Ultimate Frisbee Score Keeper — Wear OS App

**Status:** v1 complete (all 7 phases) + team names/colours with true-colour backgrounds (sections 11-12), all verified on a real TicWatch Pro 5 Enduro
**Last updated:** 2026-09-07
**Target device:** TicWatch (primary), any Wear OS 3+ smartwatch (secondary)
**Repo:** https://github.com/DerekVeenstra/ultimate-score

---

## 1. What this is

A standalone Wear OS watch app that keeps the score of a pickup Ultimate game. Two numbers,
a deliberate gesture to increment either one, and an undo. Nothing else. It has to be usable
in three seconds while sweaty, out of breath, and half-watching the disc.

### Design principles

1. **Glanceable.** The score must be readable at arm's length without focusing.
2. **Un-mis-tappable.** A point should never be scored by a sleeve, a pocket, or a catch.
3. **Never loses state.** A dead battery or a crash mid-game must not lose the score.
4. **No phone.** Works standalone at a field with no phone in reach and no signal.

### Explicitly out of scope for v1

Game clock, point timer, soft/hard cap, USAU game-to/win-by-2 rules, halftime, point history,
pull tracking, O/D line, phone companion, cloud sync, multi-game stats. Section 9 sketches how
v1 leaves room for these; none of them get built until v1 works on the real watch.

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Platform | Standalone Wear OS app | No phone dependency at the field |
| Language | Kotlin | Only first-class option for Wear OS |
| UI | Jetpack Compose for Wear OS (`androidx.wear.compose`) | Current supported toolkit; handles round/square screens |
| `minSdk` | 30 (Wear OS 3) | Covers TicWatch Pro 3 / E3 / Pro 5 / Ultra and essentially every watch sold since 2021 |
| `compileSdk` / `targetSdk` | 37 | Current stable; bumped from 35 in Phase 1 — current AndroidX libs require it |
| Scoring gesture | **Press and hold ~400 ms** on a team's half | One gesture, no confirm dialog, physically impossible to trigger accidentally |
| Undo | Single tap on a small dedicated control | Non-destructive enough that a confirm step would just be friction |
| Always-on | Yes — custom ambient rendering | Glance at the score without the raise-to-wake dance |
| Persistence | Jetpack DataStore (Preferences), written on every change | Survives crash, force-stop, and battery pull |
| Package | `com.veenstra.ultimatescore` | Change before first build if you want something else |
| Project dir | `/Users/derekveenstra/dev/ultimate-score` | This folder |

### Why hold-to-score instead of tap-and-confirm

Both were on the table. Hold-to-score wins because it is *one* gesture instead of two, and the
feedback can be continuous: a ring fills around the edge of the screen while you hold, and the
watch buzzes the instant the point commits. You learn the timing in one game and never look at
the screen to do it again. Tap-and-confirm needs you to read the screen twice.

If hold-to-score turns out to feel slow on the real device, the fallback (already accounted for
in the architecture — it is a single composable swap) is tap-to-arm + tap-to-commit with a 3-second
arm timeout.

---

## 3. Screen design

One screen. Round-safe: all content lives inside the inscribed circle so nothing clips on a round
TicWatch, and the layout is centered so it also looks right on a square watch and one with a chin.

### Interactive (screen on)

```
        ╭───────────────╮
        │      US       │   ← team label, small
        │      12       │   ← huge numeral, hold this half to score
        │───────────────│   ← divider
        │       9       │
        │     THEM      │
        │      ↺        │   ← undo, small, bottom
        ╰───────────────╯
```

- Top half = your team, bottom half = opponents. Two large hold targets, each ~45% of screen height.
- Holding either half draws a progress arc around the screen edge on that side; completing it
  increments and fires a haptic click. Releasing early cancels with no change.
- Score numerals sized to stay legible at 3 digits (a 15–13 game, or a blowout).
- Undo control is small and out of the way, at the bottom, tinted so it does not compete with
  the numbers. Tapping it pops the last point. Disabled/dimmed when history is empty.
- "New game" lives behind a long-press on the undo control, then a full-screen confirm.
  (Right-swipe is reserved by Wear OS for back/dismiss — do not bind anything to it.)

### Ambient (always-on)

- Black background, no filled shapes, thin outlined/light-weight numerals only — burn-in safety.
- Content offset by a few pixels on each ambient update to spread wear.
- No undo control, no arcs, no color. Just `US 12 / THEM 9`.
- Nothing animates and nothing ticks; the app only redraws when the score changes or the system
  issues an ambient update.

---

## 4. Architecture

Deliberately small. One activity, one screen, one ViewModel, a pure reducer, and a repository.

```
MainActivity (ComponentActivity)
  └── WearApp()                     @Composable — AppScaffold, theme, ambient wiring
        ├── ScoreScreen()           interactive rendering + gesture handling
        └── AmbientScoreScreen()    ambient rendering
              ↑ state
        ScoreViewModel              StateFlow<GameState>, exposes score/undo/newGame
              ↑
        ScoreRepository             DataStore-backed load + save
              ↑
        GameState / GameReducer     pure Kotlin, no Android deps → unit testable
```

### State model

```kotlin
enum class Team { US, THEM }

data class ScoreEvent(val team: Team, val atMillis: Long)

data class GameState(
    val history: List<ScoreEvent> = emptyList(),
) {
    val us: Int   get() = history.count { it.team == Team.US }
    val them: Int get() = history.count { it.team == Team.THEM }
    val canUndo: Boolean get() = history.isNotEmpty()
}
```

Scores are *derived* from the event log rather than stored as two counters. This makes undo
trivially correct, makes the state impossible to desync, and is exactly the log that a future
point-history or timer feature would need — at no cost today.

### Reducer

```kotlin
sealed interface GameAction {
    data class Score(val team: Team) : GameAction
    data object Undo : GameAction
    data object NewGame : GameAction
}

fun reduce(state: GameState, action: GameAction, now: Long): GameState
```

Pure function, no Android imports, fully unit tested on the JVM.

### Persistence

- Serialize `history` to a compact string in DataStore (e.g. `U:1699...,T:1699...`) or JSON via
  `kotlinx.serialization`. Either is fine; pick JSON if it is already on the classpath.
- Write on **every** state change — a game is at most ~30 events, so cost is irrelevant.
- Read once at startup, before first composition, so the watch never flashes 0–0 over a live game.

### Ambient wiring

- `AmbientLifecycleObserver` (`androidx.wear:wear`) registered from the activity, exposing
  ambient on/off and burn-in/low-bit hints to the composable tree.
- `<uses-permission android:name="android.permission.WAKE_LOCK" />` in the manifest — required
  for always-on.
- Note: Wear OS eventually drops apps out of always-on to save battery. Expect the app to be
  backgrounded after a long idle stretch; restoring from DataStore covers it.

### Manifest essentials

```xml
<uses-feature android:name="android.hardware.type.watch" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<application ...>
  <meta-data android:name="com.google.android.wearable.standalone" android:value="true" />
  <activity android:name=".MainActivity"
            android:exported="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.DeviceDefault">
    <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
  </activity>
</application>
```

---

## 5. Toolchain setup (nothing is installed yet)

Verified on this machine: macOS 14.6.1, Apple Silicon (arm64), **no JDK**, **no Android SDK**,
**no Android Studio**, **no Gradle**, **no adb**.

1. **Install Android Studio** (brings the JDK, SDK, Gradle, adb, and the emulator in one shot).
   Alternative if you'd rather stay on the command line: a JDK 17 via Homebrew plus the Android
   `commandlinetools` — more setup steps, and you still need an emulator for iteration.
2. In the SDK Manager, install: Android SDK Platform 35, Build-Tools 35, Platform-Tools,
   and the **Wear OS arm64 system image** (Apple Silicon needs arm64, not x86).
3. Create an AVD: **Wear OS Large Round, API 34** — closest match to a TicWatch Pro 5's screen.
4. Add `platform-tools` to `PATH` so `adb` works from the shell.

**Deploying to the real TicWatch: there is no USB data connection.** The charging pins are power
only. You must use wireless debugging:
Watch → Settings → System → About → tap Build number 7× → Developer options → enable
**ADB debugging** and **Wireless debugging** → pair from the Mac with `adb pair <ip>:<port>`
then `adb connect <ip>:<port>`, watch and Mac on the same Wi-Fi.

---

## 6. Build phases

Each phase ends with something runnable. Do not start a phase before the previous one runs.

### Phase 0 — Toolchain ✅ done (2026-09-07)
Install per section 5. **Done when:** `adb devices` lists a booted Wear OS emulator.

- Android Studio installed via `brew install --cask android-studio` (bundles JBR/JDK 25 at
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home`, no separate JDK needed).
- Command-line SDK tools installed manually (Studio no longer bundles `sdkmanager`) to
  `~/Library/Android/sdk`. Installed: `platform-tools`, `platforms;android-35`,
  `build-tools;35.0.0`, `emulator`, `system-images;android-34;android-wear;arm64-v8a`.
- AVD created: `Wear_Large_Round_API34` (device profile `wearos_large_round`, Wear OS 5 /
  Android 14, arm64). Boots in ~30s; verified `adb devices` shows `device` and
  `sys.boot_completed=1`.
- `ANDROID_HOME`, `JAVA_HOME`, and `PATH` (adb/emulator/cmdline-tools) added to `~/.zshrc`.
  **Open a new terminal tab (or `source ~/.zshrc`) to pick these up.**
- Launch the emulator manually any time with:
  `emulator -avd Wear_Large_Round_API34 -no-audio -no-boot-anim`

### Phase 1 — Skeleton ✅ done (2026-09-07)
New Gradle project, Wear OS "Empty Compose Activity" style, correct manifest, package name,
`minSdk` 30. **Done when:** a hello-world screen runs on the emulator.

- Hand-written Gradle project (no Android Studio wizard needed): `settings.gradle.kts`,
  root/`app` `build.gradle.kts`, version catalog at `gradle/libs.versions.toml`.
- **AGP 9 has built-in Kotlin support** — no `org.jetbrains.kotlin.android` plugin, and
  `android.kotlinOptions{}` is gone (moved to `kotlin.compilerOptions{}` if ever needed). The
  Compose *compiler* plugin (`org.jetbrains.kotlin.plugin.compose`) is still required separately.
  See https://developer.android.com/build/migrate-to-built-in-kotlin
- Versions used (checked against dl.google.com / repo1.maven.org on 2026-09-07, not guessed):
  AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.08.00, Wear Compose Material3/Foundation 1.6.2,
  Activity Compose 1.13.0, DataStore Preferences 1.2.1, Gradle 9.7.1.
- **`compileSdk`/`targetSdk` bumped to 37** (not 35 as originally planned in section 2) — current
  AndroidX libraries (`activity-compose` 1.13.0, `compose-ui` 1.12.0) require it. Installed
  `platforms;android-37.2` and `build-tools;37.0.0`.
- Gradle wrapper generated via a temporary `brew install gradle`, pinned to 9.7.1, committed to
  the project (`./gradlew` from here on — no need for the Homebrew gradle again).
- `MainActivity.kt` + `WearApp()` composable using `androidx.wear.compose.material3`, built with
  `./gradlew :app:assembleDebug`, installed and launched on `emulator-5554`, confirmed rendering
  correctly via screenshot (round screen, centered text, dark theme).
- **Note:** a physical Pixel phone was transiently visible over `adb` during this session
  (USB-connected to this Mac) — untouched, not the target device. When multiple devices are
  attached, use `adb -s emulator-5554 ...` (or `-s <TicWatch serial>` once paired) to target
  explicitly.

### Phase 2 — Core logic (no UI) ✅ done (2026-09-07)
`GameState`, `ScoreEvent`, `GameAction`, `reduce()`, `ScoreViewModel`. Unit tests for: score both
teams, undo restores previous, undo on empty is a no-op, undo then score, new game clears.
**Done when:** `./gradlew test` passes on the JVM.

- `GameState.kt`, `GameAction.kt` (sealed `GameAction` + pure `reduce()`), `ScoreViewModel.kt`
  (in-memory `StateFlow<GameState>`; DataStore persistence lands in Phase 4).
- `GameReducerTest.kt` (9 cases) + `ScoreViewModelTest.kt` (4 cases) — 13/13 passing, verified
  against the actual JUnit XML report (`tests="13" failures="0" errors="0"`), not just Gradle's
  exit code.
- Added `kotlinx-coroutines-core` 1.11.0 as an explicit dependency (was only transitive before)
  for `MutableStateFlow`/`update`.
- `./gradlew :app:assembleDebug` still succeeds — no regression to the Phase 1 skeleton.

### Phase 3 — Interactive UI ✅ done (2026-09-07)
Two-half layout, huge numerals, hold-to-score with the progress arc, haptic on commit, undo
control. **Done when:** you can play a full game on the emulator with a mouse.

- `ScoreScreen.kt`: `WearApp` (routes between the score screen and the new-game confirm),
  `ScoreScreen` (the two-half layout), `HoldToScoreZone` (press-and-hold gesture + progress arc),
  `ScoreHoldArc` (Canvas-drawn ring, top edge for US / bottom edge for THEM), `UndoControl`
  (tap = undo, long-press = ask to start a new game), `NewGameConfirmScreen`.
- Gesture implemented with `detectTapGestures(onPress = ...)` + `Animatable` animating 0→1 over
  400ms (`HOLD_DURATION_MS`); a release before completion cancels the job and animates back to 0
  over 150ms. Commit fires `HapticFeedbackType.LongPress` and calls back into the ViewModel.
- Verified on the emulator via `adb shell input swipe/tap` (a same-point swipe simulates a held
  press for its duration) + screenshots, not just "it compiles":
  - 150ms tap on a zone → does **not** score (confirmed against a screenshot at 0–0).
  - 600ms hold on US, then THEM → scores exactly once each, arc renders correctly mid-hold as a
    semicircle sweeping the correct edge.
  - Tap undo → pops exactly the last point.
  - Long-press undo → shows the New Game confirm screen with the *actual* current score
    interpolated into the text.
  - Cancel → returns to the game unchanged; Start new game → resets to 0–0 and undo dims.
  - Swept logcat across the whole session: no exceptions/crashes from the app package (only
    unrelated emulator system noise — no network in the sandbox, clock sync warnings).
- All Compose/Wear API calls I wrote without prior confirmation (`MaterialTheme.colorScheme`,
  `viewModel()`, `combinedClickable`, `HapticFeedbackType.LongPress`) compiled correctly on the
  first try — no guesses needed correcting.

### Phase 4 — Persistence ✅ done (2026-09-07)
DataStore repository, load-before-first-frame, save on every change.
**Done when:** force-stopping the app mid-game and reopening it restores the exact score.

- `ScoreHistoryStore` interface + `DataStoreScoreRepository` (`ScoreRepository.kt`) — Jetpack
  DataStore Preferences, one string key. Encoding is the plan's "compact string" option (not
  JSON): `TEAM:MILLIS` pairs joined by commas, e.g. `US:1699...,THEM:1699...`. Malformed entries
  are dropped rather than crashing on a corrupt preference.
  `ScoreViewModel` takes `historyStore: ScoreHistoryStore? = null` — defaulting to `null` keeps
  it constructible with zero Android framework dependency in plain JVM tests (Phase 2's tests
  needed no changes). Production wiring is `rememberScoreViewModel()` in `ScoreScreen.kt`, via
  `viewModelFactory { initializer { ... } }` — another API guess that compiled correctly first
  try.
- **On "load before first composition":** a literal synchronous read isn't available from
  DataStore (it's Flow-based/async), so instead `ScoreViewModel.isReady: StateFlow<Boolean>`
  starts `false` when a store is present; `WearApp` renders a blank black screen (not a `0-0`
  placeholder) until it flips `true`. Same guarantee the plan wanted — the real score is never
  shown as reset — implemented without blocking the main thread.
  Every `score()`/`undo()`/`newGame()` call also fires an async save of the resulting history.
- Tests: `ScoreViewModelPersistenceTest.kt`, 5 cases against an in-memory `FakeScoreHistoryStore`
  (no Android framework, no real DataStore) — load-on-start, ready-with-no-store, and that
  score/undo/newGame each persist the correct resulting history. Uses
  `Dispatchers.setMain(UnconfinedTestDispatcher())` so `viewModelScope` coroutines run
  synchronously in the test. Full suite: **18/18 passing** (9 reducer + 4 ViewModel + 5
  persistence), verified against the JUnit XML report.
- **On-device acceptance test, run for real, not simulated:** fresh install (explicit
  `adb uninstall` first) → played to **3-2** → `adb shell am force-stop` → confirmed via
  `pidof` that the process was actually dead (not just backgrounded) → relaunched → **3-2
  restored exactly**. Logcat swept clean of any app-level exceptions across the whole test.

### Phase 5 — Ambient ✅ done (2026-09-07)
Ambient observer, ambient composable, burn-in-safe rendering, pixel shifting.
**Done when:** the emulator's ambient mode shows a legible score with no filled areas.

- **API surface differs from the original plan.** `AmbientLifecycleObserver` (the
  callback-based, Activity-level API named in section 4) is legacy. Current Wear Compose
  (`androidx.wear.compose:compose-foundation` 1.6.2, already a dependency) instead exposes
  `LocalAmbientModeManager` — a `CompositionLocal` read as
  `LocalAmbientModeManager.current?.currentAmbientMode`, giving an `AmbientMode` sealed type
  (`Interactive` / `Ambient`, the latter carrying `isBurnInProtectionRequired` and
  `isLowBitAmbientSupported`). `rememberAmbientModeManager()` creates the manager; it's provided
  via `CompositionLocalProvider` at the top of `WearApp`.
  - One real dead-end during this phase: I initially wired ambient-tick updates through
    `AmbientTickEffect` (as an official-looking doc example suggested) — it compiled-adjacent
    but failed with "Unresolved reference" despite the symbol existing as public JVM bytecode.
    Decompiling the actual `compose-foundation-1.6.2.aar` via `javap` showed why: it's
    Kotlin-`internal` to that module (public bytecode, restricted at the Kotlin-metadata level).
    The real public entry point is `AmbientModeManager.withAmbientTick`, a suspend function on
    the interface — wired via a `LaunchedEffect` instead. Verified against decompiled bytecode,
    not just a search result.
- `AmbientScoreScreen.kt`: black background, `FontWeight.Light` numerals in a dim gray
  (`0xFFB0B0B0`, not pure white — less power draw, less burn-in risk), no arcs, no undo control,
  nothing animates. A small `BURN_IN_OFFSETS` list (4 positions, a few dp apart) is cycled by
  `withAmbientTick`'s callback so the content nudges periodically.
- **Verified on the emulator, not just compiled.** `adb shell input keyevent KEYCODE_SLEEP`
  (not `KEYCODE_POWER`, which just exits to the watch face) triggers our app's ambient state
  with `ambient_enabled=1` already set on this AVD; `KEYCODE_WAKEUP` returns to interactive.
  Confirmed round-trip: interactive (8–5) → ambient (renders "US 8 / THEM 5" correctly, thin
  gray text, black background, no arcs/undo) → interactive again, score intact throughout.
  Logcat swept clean of app-level exceptions.
- **Honest limitation:** I could not visually confirm the burn-in pixel-shift firing mid-session
  — the emulator evicted the app from ambient back to the watch face after ~70s (normal Wear OS
  platform behavior; preventing it requires an Ongoing Activity, explicitly out of scope per
  section 1). The offset-cycling code path is wired through the real public
  `withAmbientTick` API and compiles/runs without error, but a live position-shift was not
  directly observed. Worth a longer manual check in Phase 7 on the real watch, which won't have
  the emulator's short ambient-hold timing.
- Full test suite still 18/18 passing; `assembleDebug` still succeeds.

### Phase 6 — Polish ✅ done (2026-09-07)
App icon, app name, round/square/chin layout check, 3-digit score sizing, tint and contrast pass
in direct sunlight conditions, new-game confirm screen.
**Done when:** it looks finished on both a round and a square AVD.

- **App icon** replaced (was a placeholder white disc from Phase 1): a flying-disc glyph with
  two motion lines, in the new accent color, on the existing black adaptive-icon background
  (`ic_launcher_foreground.xml`). **Not visually confirmed as a rendered launcher tile** — the
  AOSP Wear system image these AVDs run doesn't include the real Google Wear OS launcher (no
  app-drawer UI available to screenshot it in), so it's only confirmed to compile/resource-link
  cleanly. Worth a glance in Phase 7 on the real watch, which will have the genuine launcher.
- **New accent color** `#00E5A0` (a fixed saturated teal-green, not the Wear Compose theme's
  default `colorScheme.primary`) used for both the hold-arc and the launcher icon, so they read
  as the same app and so the arc doesn't wash out under a future theme change or in bright light.
- **New-game confirm screen**: buttons are now rounded pills (`RoundedCornerShape(20.dp)`) at
  40dp height instead of plain rectangular 36dp boxes — closer to Wear Material button
  conventions and a larger touch target.
- **Real bug found and fixed via the square-AVD check** (exactly the check this phase's "done
  when" calls for): on `Wear_Square_API34` (360px physical, 320dpi → only 180dp of actual
  height), the original fixed-weight footer (`weight(0.15f)`) and fixed-dp text sizes (52sp
  numeral tuned against the round AVD's 227dp height) clipped "THEM" and the undo icon off the
  bottom edge. Root cause confirmed empirically, not guessed: `wm density` showed all three AVDs
  share 320dpi, so the square screen genuinely has 21% less vertical space than the round one at
  the same density — not an insets or stale-build issue (both checked and ruled out first).
  **Fix:** `ScoreScreen` now wraps its content in `BoxWithConstraints`, computes a
  `heightRatio = maxHeight / 227.dp` against the round AVD as reference, and scales the numeral
  size, label size, footer height, and undo-icon size by that ratio (each clamped to a sane
  minimum). This is a genuine "works for any Android smart watch" fix, not a one-off patch for
  the three devices tested — any future screen height scales the same way.
- **Verified on three form factors, before and after the fix, via real screenshots**:
  `Wear_Large_Round_API34` (454px/227dp, the tuning reference), `Wear_Square_API34` (360px/180dp,
  where the bug was), and `Wear_Small_Round_API34` (384px/192dp, the tightest circular curvature)
  — all three new AVDs created this phase. Confirmed clipping before the fix and full visibility
  with comfortable margins after, on the square AVD specifically (the one that was broken).
- **3-digit score sizing** stress-tested for real, not just eyeballed: 105 real hold-gestures
  (not hand-crafted DataStore data) scored on **all three** form factors. "105"/"103" rendered
  with comfortable margin, no clipping against the round bezel or the screen edge, on every
  device — including the smallest/tightest one.
- **Sunlight contrast**: can't be tested in an emulator. Design choices made with it in mind —
  bold white numerals on pure black (near-maximum contrast), a saturated fixed accent color for
  the arc (won't wash out under a themed pastel), rounded-pill buttons with strong fill/text
  contrast. Real verification is a Phase 7 on-device, outdoors task.
- App name (`"Ultimate Score"`, set in Phase 1) reviewed and kept — concise, fits the watch's
  app-list label width.
- Full test suite still 18/18 passing throughout every change in this phase; `assembleDebug`
  succeeded after each edit.

### Phase 7 — Real device ✅ done (2026-09-07)
Wireless debug onto the TicWatch. Play a real game with it.
**Done when:** it survived a game and the hold timing feels right on the wrist.

- **Confirmed device:** TicWatch Pro 5 Enduro (`model:dace`), not just "a TicWatch" — good, it's
  the min-SDK-30 target class from PLAN.md section 2.
- Paired over wireless ADB (`adb pair` with the 6-digit code + pairing port, then `adb connect`
  to the separate main IP:port — these are two different ports on this watch, as section 5
  warned they might be) and confirmed connected: `adb devices` lists it alongside the emulator.
- Installed and launched the actual `assembleDebug` APK (no changes since Phase 6) on the real
  watch, confirmed via a real screenshot pulled off the device: renders correctly — same clean
  layout as the emulator, 0-0, dark theme, US/THEM labels legible.
- One real-device-only wrinkle: `adb shell am start` does **not** wake a real watch's physical
  display the way it does on the emulator — the first screenshot came back solid black because
  the screen was asleep even though the activity had started. `adb shell input keyevent
  KEYCODE_WAKEUP` fixed it. Not an app bug, just a real-hardware-vs-emulator difference worth
  remembering for any future on-device debugging.
- **Real bug found by the user playing on the actual watch: the US/THEM divider wasn't
  vertically centered.** Root cause: the undo control was a same-weight third row below both
  score zones, so the divider sat at the center of "screen minus footer," not the center of the
  screen — off by about half the footer's height, visibly asymmetric on a round face.
  - First fix attempt (give US/THEM explicit heights so US-alone equals THEM-plus-footer)
    correctly centered the divider, but on the real TicWatch Pro 5 Enduro (466px/320dpi = 233dp
    tall — actually *taller* than the 227dp round AVD this was tuned against) it clipped "THEM"
    off the bottom: shrinking only THEM's zone by the footer's height left it without enough
    room for numeral+label at the same size US gets.
  - **Real fix:** matches PLAN.md section 3's original mockup, where undo sits *within* the
    bottom half rather than in a row below both halves. US and THEM now get exactly equal
    heights (divider is trivially centered, and THEM gets exactly as much content room as US),
    and the undo control is overlaid on top of the bottom edge via `Modifier.align
    (Alignment.BottomCenter)` instead of occupying its own layout row. A hold starting on the
    small undo-icon area is captured by its own tap/long-press handler rather than the
    HoldToScoreZone underneath it — ordinary Compose pointer-input arbitration (a `clickable`'s
    detector requires an unconsumed down event, so the overlaid control naturally wins there).
  - The overlay fix's *second* round also had a bug, also caught by the user on the real
    watch: the arc-and-content Box centered THEM's numeral+label across its *entire* zone
    height, and that centered content block was taller than the space actually clear of the
    undo overlay — so "THEM" visually collided with/sat under the undo icon. No amount of
    nudging the centering offset could fully fix this (the content is simply taller than the
    clear space at that font size); the real fix confines THEM's content to an explicit
    sub-box — `contentAreaHeight = halfHeight - footerHeight`, top-aligned within THEM's zone
    (so content starts right after the divider and can never reach down into where undo sits) —
    making the overlap geometrically impossible rather than something arithmetic has to land
    exactly right. Numeral/label sizes are now also driven off this tighter, correct constraint
    (`min(usContentHeight, themContentHeight)`) rather than the full screen height.
  - Verified via **four** install-and-screenshot round-trips directly on the real watch (not
    the emulator) across this whole back-and-forth: confirmed the original off-center bug,
    confirmed the first fix's new clipping bug, confirmed the second fix's new overlap bug,
    confirmed the final version — divider centered, "THEM" fully visible with a clear gap above
    the undo icon, no overlap. Full 18/18 test suite re-verified after each of the three code
    changes.
- **Confirmed by Derek playing on the real watch:** hold-to-score timing, undo, new game,
  ambient/always-on, and the app icon all check out. v1 is done.

---

## 7. Testing

- **Unit (JVM):** the reducer and ViewModel. This is where the actual correctness lives, and it
  runs in a second with no emulator.
- **Manual on emulator:** gesture feel, layout on round + square + chin AVDs, ambient rendering,
  process-death restore (`adb shell am force-stop com.veenstra.ultimatescore`).
- **Manual on device:** the only test that matters for gesture timing, sunlight legibility, and
  battery. Nothing else substitutes for wearing it.
- Compose UI tests are deliberately skipped in v1 — one screen, and the logic worth testing is
  already pure.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| No USB debugging on TicWatch Pro 5 | Wireless ADB, documented in section 5; verify it works in Phase 0, not Phase 7 |
| Apple Silicon emulator images | Install arm64 Wear OS system images specifically |
| 400 ms hold feels wrong on-wrist | Make the duration a single constant; tune it in Phase 7 |
| Always-on drains battery over a tournament | Ambient rendering is black + thin strokes; if it still drains, make always-on a toggle |
| System evicts the app from always-on | DataStore restore makes relaunch instant and lossless |
| Sunlight legibility | Max contrast, heaviest weight numerals that fit, verify outdoors in Phase 7 |

---

## 9. Room left for later

The event-log state model is the hook for everything deferred:

- **Timers** — `ScoreEvent.atMillis` is already recorded, so point duration and game elapsed are
  derivable with no migration.
- **USAU rules** — game-to, win-by-2, and cap are pure functions of `us`/`them`; they slot in next
  to the reducer.
- **Point history** — the log *is* the history; it only needs a screen.
- **Phone companion** — a serialized event log is exactly what would go over the Data Layer.

None of this gets built in v1.

---

## 10. Decisions (round 2)

| Decision | Choice | Why |
|---|---|---|
| Team labels | Fixed "US"/"THEM" | No settings screen, no text entry on a tiny watch keyboard |
| Screen wake during play | Normal ambient timeout, no wake lock | Best battery life over a tournament day; screen wakes on wrist-raise/tap same as any other app |
| Theme | Dark only | Matches Wear OS system/ambient conventions, OLED-friendly; verify sunlight legibility on-device in Phase 7 before adding a second theme |

No open questions remain. Ready to start Phase 0.

*(The "fixed US/THEM labels" decision above was superseded post-v1 — see section 11.)*

---

## 11. Post-v1: team names and colours (2026-09-07)

Starting a new game now lets you name and colour each team. Both are optional and default to
exactly the v1 look, so nothing about the "just keep score" path got slower.

### Decisions

| Decision | Choice | Why |
|---|---|---|
| Background treatment | **True colour** (superseded from deep tint — see below) | Derek tried the deep-tint version and didn't like it; asked for true colours instead. |
| Name entry | Presets for US, free text for the opponent | Your own team is one of a few knowns; opponents change every game. Avoids a keyboard for the common case. |
| Setup flow | Setup screen **replaces** the old confirm | Score-loss warning is inline instead, so a quick start is the same tap count as v1. |
| Palette | 8 fixed swatches | Tappable on a 1.4" screen; a hue picker would be miserable mid-game. |
| Ambient mode | Stays **pure black** regardless of team colours | Ambient exists for burn-in and battery (section 3) — a tinted background works directly against that. |
| Presets | "Flaming Nipples" (pink), "Flaming Throws" (gray), plus plain "US" | As requested. Picking one sets name *and* colour; the swatches still override afterwards. |
| Stickiness | Setup pre-populates from the current game | A recurring team stays selected; no re-picking every game. |

### Implementation

- `TeamConfig.kt` — `TeamColor` (8 entries, each with swatch + tint ARGB as plain `Long`) and
  `TeamConfig` (name + colour). Deliberately **no Compose/Android imports** so the whole model
  layer stays JVM-testable; the UI converts to `Color` at point of use.
- `GameState` gained `usTeam`/`themTeam`; `GameAction.NewGame` became a data class carrying the
  chosen configs (defaulting to US/THEM), so `NewGame()` still means "reset to the v1 look".
- `NewGameSetupScreen.kt` — scrollable `ScalingLazyColumn`: title, inline score-loss warning,
  US presets, US palette, opponent name row, opponent palette, Start/Cancel. The opponent name
  uses Wear's standard `RemoteInputIntentHelper` input activity (keyboard **and** voice
  dictation) via `androidx.wear:wear-input` 1.2.0; a blank result falls back to "THEM".
- Persistence extended from one key to five (`history`, `us_name`, `us_color`, `them_name`,
  `them_color`). The codecs were **extracted to pure top-level functions** so the round trip —
  including reading back a game saved before this feature existed — is unit-testable.
- Score screen: each half's background is its team's tint; labels are the team names, uppercased,
  `maxLines = 1` with ellipsis, and shrunk ~22% when either name runs long (so "FLAMING NIPPLES"
  fits without truncating). Ambient lines shrink by length the same way.

### Verified on the emulator, by screenshot at each step

Backward compatibility (a game already saved on-device loads with the plain v1 look), the full
setup screen and its scrolling, preset selection auto-applying its colour, the palette, the real
`RemoteInputActivity` round trip (typed "Sockeye", got it back), starting a game, scoring on the
tinted backgrounds, persistence across a **confirmed process kill**, ambient rendering with a long
name on a still-pure-black background, the gray "Flaming Throws" preset, and reverting to no
colour restoring the exact v1 appearance while keeping a custom name.

Tests went from 18 to **39**, all passing — new suites for the team model (`TeamConfigTest`,
including an assertion that *every* palette tint is dark enough to keep white numerals legible)
and for the serialization codecs (`ScorePersistenceCodecTest`, including the pre-feature
save-data case and corrupt-input handling).

### Verified on the real TicWatch Pro 5 Enduro (2026-09-07)

- Confirmed on actual AMOLED, not just the emulator: the deep pink tint for Flaming Nipples is
  clearly distinguishable from pure black (THEM's default half), and "FLAMING NIPPLES" is fully
  legible at the shrunk label size — no truncation. This was the main open question after the
  emulator pass, since real AMOLED contrast/color reproduction can differ meaningfully from an
  emulator's rendering.
- Hold-to-score, undo, and persistence across a confirmed process kill all still work correctly
  with tinted backgrounds. Score survived a full ambient round-trip (1–1 before and after).
- **Tooling limitation, not an app bug:** `adb screencap` came back solid black while the watch
  was in ambient, even though `dumpsys power` showed `mWakefulness=Dozing`, the activity stayed
  `ResumedActivity`, the process was alive, and logcat had zero exceptions — all the signals a
  correctly-functioning ambient screen would show. This real watch's AOD compositing path
  appears to bypass the framebuffer `screencap` reads, unlike the emulator (no dedicated AOD
  hardware, renders ambient in software, `screencap` could see it fine there). Ambient on real
  hardware is architecturally confirmed working; a literal screenshot of it isn't obtainable
  this way, so a plain visual look by the user is the last piece of confirmation left.
- Incidentally hit and worked around a real-hardware testing wrinkle: this watch's screen timeout
  is 10s, shorter than the round-trip latency between adb commands issued a few seconds apart,
  causing several early attempts to land on an already-dimmed screen. Temporarily raised
  `screen_off_timeout` to 120s for the test session and **restored it to 10000 (its original
  value) afterward** — worth knowing if testing on-device again.
- Full crash sweep across the whole session: clean.

---

## 12. Post-v1: true colours, not tinted (2026-09-07)

Derek tried the deep-tint backgrounds from section 11 on the real watch and didn't like the
look — asked for true, full-saturation colours instead.

### The contrast problem this creates, and how it's handled

Full saturation was one of the two background options originally considered (section 11's
question round) and explicitly not picked *because* several of the 8 palette colours are light
enough that white numerals lose meaningful contrast on them — this isn't hypothetical, it's
measurable. Computing actual WCAG contrast ratios for white text against each true-colour swatch:

| Colour | vs. white text | vs. black text |
|---|---|---|
| PINK | 4.35:1 | **4.83:1** |
| GRAY | 2.68:1 (fails 3:1) | **7.84:1** |
| BLUE | 3.12:1 | **6.72:1** |
| GREEN | 2.78:1 (fails 3:1) | **7.56:1** |
| ORANGE | 2.16:1 (fails badly) | **9.74:1** |
| PURPLE | **6.30:1** | 3.33:1 |
| RED | 3.68:1 | **5.70:1** |

Keeping white text unconditionally would have made GRAY, GREEN, and ORANGE genuinely hard to
read (ORANGE in particular fails even the relaxed large-text WCAG threshold) — a real legibility
regression against design principle #1 ("Glanceable," section 1), not a cosmetic nitpick. So
implementing "true colours" **also** required picking the right text colour per background,
which the request didn't ask for explicitly but the app's core premise depends on.

### Implementation

- `TeamColor.tintArgb` removed; `swatchArgb` (already the true colour, used for the picker dot)
  is now also the background via a new `backgroundArgb` property — `NONE` still resolves to pure
  black, so the no-colour path is pixel-identical to before this and section 11's change.
- New pure functions in `TeamConfig.kt` — `relativeLuminance`, `contrastRatio`, and
  `textColorArgbFor` — implement the actual WCAG relative-luminance formula (not an approximation)
  and pick whichever of black/white contrasts better against a given background. Plain `Long`
  ARGB in, plain `Long` ARGB out, so this is fully JVM-testable with no Android dependency, same
  as the rest of the model layer.
- `HoldToScoreZone` and `UndoControl` now take an explicit `textColor`/`iconColor` computed from
  each team's `backgroundArgb`, instead of assuming white. The secondary label text's existing
  70%-alpha dimming was re-checked against the alpha-blended result for every colour (not just
  assumed safe) — every case still clears 3:1 even blended, so no change was needed there.
- The divider between US/THEM was **almost** changed to a dark line to "fit" colour or
  backgrounds better, then reverted before shipping: the app's window background is black, so a
  translucent *black* divider would have been nearly invisible in the default (no colour chosen)
  case — by far the most common one. Caught by checking what's actually behind it before
  changing it, not by trial and error on-device.

### Verified on the real TicWatch Pro 5 Enduro

Specifically re-tested the two colours the contrast table flags as needing black text — GRAY and
ORANGE — since those were the ones a wrong guess would have broken most visibly. Both render at
full saturation with crisp black numerals and black undo icon, clearly legible, on actual AMOLED.
Also re-confirmed hold-to-score, scoring, and persistence across a **confirmed process kill**
(force-stopped, verified via `pidof` the process was dead, relaunched) all still work correctly
with true-colour backgrounds — score and colours both survived intact. Full crash sweep: clean.

Test suite grew from 39 to **42** — `TeamConfigTest` gained WCAG-contrast assertions (every
colour must clear 3:1, the large/bold-text threshold that applies to these numerals) replacing
the old "every tint is dark enough" checks that no longer apply now that tinting is gone.
