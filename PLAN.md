# Ultimate Frisbee Score Keeper — Wear OS App

**Status:** v1 complete (all 7 phases) + team names/colours with true-colour backgrounds (sections 11-12, verified on a real TicWatch Pro 5 Enduro) + saved team presets on both sides (section 13, unit-tested; not yet verified on-device)
**Last updated:** 2026-09-11
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
| Name entry | Presets for US, free text for the opponent (**superseded by section 13** — both sides are now saved presets, no free text on either side) | Your own team is one of a few knowns; opponents change every game. Avoids a keyboard for the common case. |
| Setup flow | Setup screen **replaces** the old confirm | Score-loss warning is inline instead, so a quick start is the same tap count as v1. |
| Palette | 8 fixed swatches | Tappable on a 1.4" screen; a hue picker would be miserable mid-game. |
| Ambient mode | Stays **pure black** regardless of team colours | Ambient exists for burn-in and battery (section 3) — a tinted background works directly against that. |
| Presets | "Flaming Nipples" (pink), "Flaming Throws" (gray), plus plain "US" (**superseded by section 13** — these hardcoded presets were deleted; both lists now start empty and are user-created) | As requested. Picking one sets name *and* colour; the swatches still override afterwards. |
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
  **Superseded by section 13**: the per-game colour palette on this main screen is gone (colour
  is now only set when creating/editing a saved preset), and "US presets"/"opponent name row" are
  both replaced by the two saved-preset lists described there. The `RemoteInputIntentHelper`
  mechanics described here are unchanged and now live in a shared helper (section 13).
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

---

## 13. Post-v1: saved team presets, both sides (2026-09-07)

Section 11 gave "your team" three hardcoded presets and left the opponent as free text typed
fresh every game with no memory. Derek asked to replace that entirely with user-created,
watch-local, persisted presets on *both* sides — every decision below was specified by him, not
inferred.

### Decisions

| Decision | Choice | Why |
|---|---|---|
| Lists | Two independent lists: "my teams" and "opponents" | Stored, managed, and rendered separately — there's no shared numbering or ordering between them. |
| Starting content | Both lists start **empty**; `TeamConfig.US_PRESETS` deleted outright | The three hardcoded presets ("Flaming Nipples", "Flaming Throws", plain "US") aren't seeded into the new store — a fresh install has nothing until the user creates something. |
| Creation | Inline: a "+ New team..." row at the end of each list — name, then colour, then it's saved *and* selected for this game in one pass | No separate "manage teams" screen to visit first; creating a team is part of picking one. |
| Management | Long-press a preset row (`Modifier.combinedClickable`) to rename/recolour/delete it | Tap picks a team for this game; a second gesture is needed for anything else, and long-press is the existing pattern (undo control already uses it for "new game"). |
| Colour | Removed from the main setup screen entirely; only set when creating or editing a preset | Colour is now a property of the *team*, not of "this game" — the per-game `ColorSwatches` row this replaces no longer makes sense once colour lives on the preset. |
| Empty selection | Still works: pick nothing on either side and you get `TeamConfig.DEFAULT_US`/`DEFAULT_THEM` — the exact v1 look | No regression to the "just keep score" path sections 1 and 11 both protect. |

### Why a separate `TeamPreset` type instead of reusing `TeamConfig`

`TeamConfig(name, color)` stays exactly as it was — it's what `GameState` and its DataStore
persistence already understand, and section 11's setup flow used *name equality*
(`usTeam.name == preset.name`) to figure out which preset was selected. That was already a latent
bug (two presets sharing a name, or a rename, would misattribute the selection) that a bare
`TeamConfig` has no way to fix, because it has nothing to be identified by other than the fields
that are also what a user edits. `TeamPreset(id, name, color)` adds exactly one thing —
a stable `id`, generated at creation time (wall-clock millis; injectable in `ScoreViewModel` for
deterministic tests) — and `toConfig()` converts to what the running game actually needs. The
setup screen now tracks selection by id, so a rename can never change *which* preset is selected,
only what it's called.

### Storage: control characters as separators, not JSON

Preset names are arbitrary user text and *will* contain the punctuation the existing codecs use as
separators — commas (history's `US:1699...,THEM:1699...`) and colons (`TEAM:MILLIS` pairs, and
`us_name`/`us_color` are separate keys but a name could still contain either character). Rather
than hand-roll an escaping scheme for two specific characters (and its inevitable edge-case bugs —
an unescaped separator inside an escaped one, doubled escape characters, etc.), `encodePresets`/
`decodePresets` (ScoreRepository.kt) use two ASCII control characters instead: U+001F (Unit
Separator) between the three fields of one preset (`id`/`name`/`color`) and U+001E (Record
Separator) between presets. A control character can never appear in ordinary typed text, so no
escaping is needed at all — and to guarantee that stays true, `sanitizeName` (TeamConfig.kt, a
generalization of section 11's inline blank-name-fallback logic in `TeamConfig.named`) strips
every control character from a name *before* a `TeamConfig` or `TeamPreset` is ever built from it,
not just at encode time. Same file, two new top-level DataStore keys (`my_team_presets`,
`opponent_team_presets`) alongside the five section 11 already added — one `DataStoreScoreRepository`
class now implements both `ScoreHistoryStore` and the new `TeamPresetStore` interface, since it's
the same underlying `game_state` preferences file. Malformed records (wrong field count, blank
id/name) are dropped rather than throwing, same policy as every other codec in this file; an
unrecognized colour name falls back to `TeamColor.NONE` via the existing `decodeColor`.

### ViewModel and selection semantics

`ScoreViewModel` gained a `presets: StateFlow<TeamPresetLists>` (two lists, same as storage) and
four actions — `addPreset`/`renamePreset`/`recolorPreset`/`deletePreset` — each taking a
`PresetGroup` (`MY_TEAMS`/`OPPONENTS`) so one set of functions serves both lists instead of
duplicating each into a "MyTeam" and "Opponent" variant. `presetStore` is optional/nullable the
same way `historyStore` already was, for the same reason: `ScoreViewModelPresetTest` constructs
the ViewModel with an in-memory fake and no Android dependency at all.

Selection lives in `NewGameSetupScreen`'s own Compose state (by preset id), not in the ViewModel,
because it's specific to "what's picked for the game about to start," not persisted state. Two
rules were specified exactly: tapping an already-selected row deselects it (falling back to the
default US/THEM identity for that side), and deleting the preset currently selected in the setup
screen must also fall back to the default rather than leaving a dangling id pointing at nothing —
both are handled in `NewGameSetupScreen` itself, at the point of the tap/delete, rather than by
having the ViewModel try to reach into UI-owned selection state it has no business touching.

### Setup screen: three screens as one piece of Compose state, not a nav graph

`NewGameSetupScreen.kt` models its flow as a private `SetupMode` (`Picking`/`ChoosingColor`/
`Editing`) rather than a second Activity or a Compose nav graph — a full nav graph would be
overhead this app doesn't need anywhere else, and the whole app is already "one screen at a time."
`Picking` is the normal two-list-plus-Start/Cancel view; tapping "+ New team..." collects a name
via the RemoteInput launcher and moves to `ChoosingColor`, which reuses section 11's
`ColorSwatches` composable (per Derek's explicit instruction not to delete it, just stop using it
on the main screen); saving there calls `addPreset` and returns to `Picking` with the new preset
selected. Long-pressing a preset row moves to `Editing`, which re-reads the *live* preset from
`presets` every recomposition (not a snapshot taken when the screen was entered) so a recolour is
reflected immediately in its own swatch highlight.

The RemoteInput text-input launcher — needed now in three places (new my-team, new opponent,
rename) where section 11 only needed it once — was factored into one `rememberTextInputLauncher`
composable that builds the intent, launches it, and parses the result, parameterized only by the
field's label. Each call site decides what a `null` (cancelled or blank) result means; every one
of them treats it as "do nothing" rather than falling back to a placeholder name, so a cancelled
creation never produces an empty-named preset.

### Verified

`./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` both green. Test suite grew
from 42 to **63**: `TeamConfigTest` lost the now-nonexistent "US presets" assertion and gained
coverage for `TeamPreset.toConfig()`, `TeamPresetLists`' per-group read/replace, and `sanitizeName`
(including a name containing the codec's own separator character); `ScorePersistenceCodecTest`
gained the full preset-codec suite (round trip, names containing commas/colons, a name containing
a raw separator character, malformed records, an unknown colour, absent/blank/empty input); a new
`ScoreViewModelPresetTest` covers add/rename/recolour/delete each updating state and persisting,
the two lists being independent, blank-rename being a no-op, and id generation using the injected
generator rather than the wall clock in tests. Not verified on-device — that's Derek's to do.

---

## 14. Post-v1: swap PURPLE for WHITE (2026-09-07)

Derek asked to replace PURPLE with WHITE in the colour picker, and to put white next to black.
There's no separate "black" `TeamColor` — [NONE] (the no-colour default) is the one that reads as
black, both by its pure-black `backgroundArgb` and its near-black `swatchArgb` dot — so WHITE was
inserted as the enum entry immediately after `NONE`, making them the first pair in the picker's
two-per-row grid (`ColorSwatches` in NewGameSetupScreen.kt renders `TeamColor.entries` in
declaration order). No layout code changed — reordering the enum was enough.

`textColorArgbFor` needed no change: it already picks whichever of black/white contrasts better
against a background, and against pure white that's black by a wide margin, so WHITE swatches get
black numerals automatically.

A saved preset with colour `PURPLE` (from before this change) decodes via the existing
"unrecognized colour name" fallback in `decodeColor` — it comes back as `NONE`, not a crash — so
no persistence migration was needed, but a team someone coloured purple before today will show up
black next time it's picked. Section 12's contrast table above still lists PURPLE's measured
ratios as a historical record of that analysis; it's not re-run for WHITE since white vs.
black/white text is not a borderline case the way several of the original 8 were.

### Verified

`./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` both green — no test
referenced `PURPLE` by name (`TeamColor.entries` is iterated generically), so nothing needed
updating for the swap itself.

---

## 15. Post-v1: real launcher icon from Derek's logo (2026-09-07)

Replaced the placeholder vector glyph (a plain flying disc with two motion lines) with Derek's
actual logo: a flying disc wreathed in flame, in the app's now-familiar "Flaming ___" spirit.

### Source file and why this went through raster, not vector

Derek supplied the logo as a `.ai` file. There's no Illustrator, Inkscape, or any SVG/PDF
converter on this Mac, so true vector path extraction wasn't on the table. What *is* available:
modern `.ai` files are PDF-compatible under the hood (this one declares itself `PDF-1.5`), and
macOS's built-in Quick Look (`qlmanage -t -s <px>`) will rasterize that PDF content at whatever
resolution is requested — including far higher than the on-screen preview implies, because it's
genuinely re-rendering the vector content each time, not upscaling a fixed bitmap. Rendered at
5333×8000 (`qlmanage -t -s 8000`), which is more headroom than any Android density bucket needs,
so going raster here cost no visible sharpness versus true vector — it just means a future edit
to the logo has to happen in the original `.ai`, not by hand-editing an Android vector drawable.

### Cropping and the background problem

Quick Look's rasterization flattens onto an opaque white canvas — it doesn't preserve the `.ai`
file's actual transparency, so the naive "make white pixels transparent" approach would have also
punched a hole through the logo's own white disc face, which is legitimately white. Fixed with a
connected-components pass (`scipy.ndimage.label` over a near-white mask, Pillow for the rest):
only the near-white *region touching the crop's outer border* gets turned transparent; the disc's
white interior, fully enclosed by its red ring, never touches that border and is untouched. Net
result: a tightly-cropped PNG with a real alpha channel, `docs/logo.png` in the repo (the
"flattened on black" version sits alongside it as `docs/logo-preview-black.png` for a quick look
without opening an image editor).

### Sizing it as an adaptive icon foreground

The artwork is a diagonal "comet" — disc in one corner, flame trailing to the opposite one — which
doesn't fill a square evenly. Checked the fit against Android's adaptive-icon safe zone (the
guaranteed-visible 66/108 of the 108dp canvas) by measuring the artwork's actual max radius from
canvas center in pixels rather than guessing: at 78% of canvas width, the farthest content point
(a flame tip) sits at 96% of the circular mask's radius — comfortably inside a circular launcher
mask with no clipping, confirmed by rendering the composited PNG through an actual circle-mask
simulation before shipping it. The two *other* corners of the square stay empty — that's the
source art's own diagonal shape, not a cropping mistake, and reads fine (arguably better) as
directional motion rather than a centered blob.

- `app/src/main/res/drawable/ic_launcher_foreground.xml` (the old vector) deleted.
- `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` (1024×1024, RGBA) added — `nodpi`
  because this is one raster asset scaled by Android at render time, not a set of per-density
  exports; `mipmap-anydpi-v26/ic_launcher.xml` needed no change since the resource name
  (`ic_launcher_foreground`) didn't change, only what backs it.
- `colors.xml`'s `ic_launcher_background` (`#000000`) is unchanged — already matched the app's
  black theme and gives the logo's flames and white disc good contrast.
- No `android:roundIcon` in the manifest and no separate round mipmap exists, so there was only
  one icon resource to replace.

Not placed anywhere else in the app yet — there's no splash/about screen today, so `docs/logo.png`
is just sitting in the repo for whenever (or if) one exists. Ask Derek before adding one rather
than assuming a spot for it.

### Verified

`./gradlew :app:assembleDebug` green; confirmed the PNG actually made it into the built APK
(`unzip -l`, uncompressed at its full 164KB, under `res/drawable-nodpi-v4/`) rather than trusting
the Gradle exit code alone. Installed to the Wear emulator and screenshotted the real app-drawer
icon at its actual on-screen size — legible and uncupped, matching the circle-mask simulation.
Not yet installed on the real TicWatch; wireless ADB was offline when this was done.

---

## 16. Post-v1: icon fit and colour follow-ups (2026-09-07)

Two rounds of feedback on section 15's icon, both from testing on the real TicWatch.

### Round 1: bottom-left of the disc was getting clipped

The circle-mask simulation in section 15 (artwork sized to 78% of canvas width, putting its
farthest point at 96% of the mask's radius) looked fine in that simulation but clipped on the real
watch. The simulation wasn't wrong so much as it was checking the wrong guarantee: Android's
adaptive-icon contract only *guarantees* the inner ~61% radius (66/108 of the icon canvas) is
visible on every compliant launcher — anything further out is shown or clipped at that launcher's
discretion, and a perfect circle happening to contain 96%-of-radius content is not the same as
that content being inside the *guaranteed* zone. Rescaled so the farthest artwork point sits at
58% of the radius — safely inside the guarantee with margin, re-verified against the same
circle-mask simulation plus a render at actual small launcher size before shipping again.

### Round 2: two more recolours

Requested change: the white sliver among the flame near the disc's bottom-right should be yellow,
and the small white circle behind the lightning bolt (inside the disc, distinct from the disc
face itself, which section 15 already made pink) should also be pink.

Both used the same connected-components technique as section 15's pink disc face — identify the
enclosed (non-background) white regions by component, then recolour by id rather than by
position, since "enclosed" already rules out touching the actual transparent background. The
small circle behind the bolt turned out to be *two* disconnected white fragments (plus a couple of
sub-300px specks) — the lightning bolt's zigzag cuts all the way across it, splitting what looks
like one circle into two separate enclosed regions — so all of those got the pink treatment
together. The bottom-right sliver used yellow sampled directly from the artwork's own flame colour
(`#FFCE00`, the dominant colour found across ~600K yellow-ish pixels in the source render) rather
than an app palette colour, since it's meant to blend into the existing flame, not stand apart
from it — unlike the disc face and small circle's pink, which was deliberately `TeamColor.PINK`
to tie the icon to the app's own palette.

### Verified

`./gradlew :app:assembleDebug` green after each round. Re-ran the circle-mask simulation and a
64×64 downscale render (to check legibility at actual tiny launcher size, not just the 1024px
working resolution) before shipping the final version — the bolt mark stays readable at both
sizes even against the now-pink background. Not yet confirmed on the real TicWatch for round 2;
wireless ADB was intermittently offline throughout this session.

---

## 17. Post-v1: icon zoom, round 3 — center on the disc, then all the way in (2026-09-07)

Two more rounds of on-device feedback, both about the icon feeling too small/washed out at
section 16's safe-but-conservative 58%-of-radius sizing.

### Round 3: "hard to see" — recenter on the disc, not the whole comet

Diagnosed *why* uniformly scaling the whole comet shape up had already failed once (section 16's
clipping report): the disc itself sits well off from the comet bounding box's own center (the
flame trail's off to one side), so centering the *bounding box* put the disc off-center too — its
own edge was almost as close to the clip boundary as the flame tip on the opposite side.
Measured this directly rather than guessing: in the section-16 composition, the disc's own pixels
reached 77% of the canvas radius (and that got clipped), while the current 58% build's disc-only
reach was a proportionally-scaled ~47% (safe, but small).

Fix: stop centering the bounding box: find the disc's own center and radius (by locating its pink
fill directly, not by reusing old hand-copied coordinates) and center *that* in the canvas
instead. This makes the disc's edge distance from canvas-center equal to just its own radius — no
extra offset penalty — so it can be sized meaningfully larger (targeted 62% of canvas radius, well
under the 77% figure already known to fail) while the flame trail, now off-center, bleeds and
clips at the canvas edge instead. That trail was always the disposable, decorative part; the disc
and lightning-bolt mark are the identity.

### Round 4: "so it doesn't show the flames on the edge at all"

Requested full elimination of visible flame, not just a bigger disc. Sampled pink-colour coverage
along circles of increasing radius from the disc's center (every 0.25° at 10px radius steps) to
find exactly how far out the disc stays **solid pink at every angle** — it holds 100% to ~410px,
then a flame accent (the bottom-right sliver from section 16, round 2) starts intruding beyond
that. Recentered and zoomed so the canvas half-side sits at 390px in that same coordinate space —
inside the measured all-pink radius with a small margin — meaning no flame-coloured pixel can
appear in frame at all, regardless of what fraction of the canvas any given launcher's mask
actually shows. The resulting icon is a plain pink disc with the lightning-bolt mark and no fire
motif visible; flagged that trade-off to Derek rather than silently discarding the flame branding.
Confirmed installed and working on the real TicWatch, not just simulated.

### Verified

`./gradlew :app:assembleDebug` green after both rounds; each was checked against a circle-mask
simulation and a 64×64 downscale render before shipping, then installed and confirmed via
`dumpsys package`'s `lastUpdateTime` on the real TicWatch (not just Success from `pm install`,
since the wireless ADB connection had a habit of dying mid-command earlier in this session).

---

## 18. Post-v1: NONE colour dot missing from the presets list (2026-09-07)

Derek made an opponent preset and recoloured it to the near-black `NONE` swatch (the one added
next to WHITE in section 14, specifically because it visually reads as "black" — see that
section's own reasoning) — and the list stopped showing any colour dot next to that team's name
at all, as if the colour hadn't saved.

It had saved; the dot was being deliberately suppressed. `SelectableRow`'s
`swatch != null && swatch != TeamColor.NONE` check predates presets entirely — it's section 11-era
logic for a world where `NONE` only ever meant "nothing chosen yet," so hiding its dot made sense.
Every row that reaches `SelectableRow` today, though, already carries a real, saved
`preset.color` — a preset coloured `NONE` is just as deliberate a choice as one coloured `PINK`,
and section 14 already established that this exact swatch is *supposed* to be pickable as "black."
Suppressing it there was simply stale.

Fix: `swatch != null` alone decides whether to draw the dot; `null` (never `TeamColor.NONE`) is
what the "+ New team..." action rows already pass to mean "no colour concept here," so nothing
about the null-check contract changed. Also added a thin translucent white outline to every dot,
in both the list row and the colour-picker grid's unselected swatches — `NONE`'s dot
(`#2A2A2A`) sits close in luminance to the list row's own translucent-white-on-black background
(closest when the row is selected), and to the picker screen's plain black background when
unselected, so without an outline the fix would have made the dot present but still nearly
invisible in exactly the cases that motivated it.

### Verified

`./gradlew :app:testDebugUnitTest :app:assembleDebug` — 63/63 tests green (no test covered this
purely-visual condition), build green. Installed and confirmed on the real TicWatch via
`dumpsys package`'s `lastUpdateTime`.

---

## 19. Post-v1: ABBA gender ratio (2026-09-10)

Derek asked for a way to pick the mixed-Ultimate gender ratio for the first point (M or F) in
setup, and to show the current point's ratio on the score card.

### The rule

"ABBA": the ratio chosen for point 1 is `A`, then the pattern is `A B B A A B B A …` — so points
1, 4, 5, 8, 9 use the chosen start and points 2, 3, 6, 7, 10, 11 use its opposite. That's the
whole of `genderForPoint(point, start)` in GameState.kt (`point % 4 == 1 || point % 4 == 0`).
The "current point" is `history.size + 1` — the one the next score will complete — so the badge
shows the ratio of the point being played right now, and stepping back with undo moves it back.

### Decisions

- **Off is the default and a first-class option.** Most pickup games don't run fixed ratios, and
  a stored `Gender?` of `null` means "not tracking" — the score card then looks exactly as it did
  before this existed, and a game saved before this feature (`abba_start` key absent) decodes to
  the same `null`. `save()` *removes* the key when off rather than writing a sentinel, so the two
  cases are byte-identical on disk.
- **`Gender { M, F }`** in the model layer (no Android imports), mirroring `Team`/`TeamColor`.
  `M`/`F` are Derek's words; the setup caption spells out "majority men / women".
- **Setup UI:** a three-way `Off / M / F` segmented control (`AbbaStartSelector`) added to the
  picking screen under a "Gender ratio · ABBA" header, pre-populated from the game in progress so
  re-opening setup mid-game keeps the choice. `onStart` grew a third arg.
- **Score card:** a small accent pill (`GenderBadge`) centred on the US/THEM divider — belongs to
  neither side, straddles both. No gesture handler of its own, so a hold that lands on the pill
  still falls through to the zone underneath. Only drawn when `currentGender != null`.
- **Ambient:** one extra bare letter below the two score lines (thin light glyph, no chrome, per
  section 3), only when tracking is on.

### Persistence

New `abba_start` string key in the same `game_state` DataStore. `decodeGender` returns `null` for
absent/unknown/blank — same forgiving shape as `decodeColor`.

### Verified

`./gradlew :app:testDebugUnitTest :app:assembleDebug` — 72/72 tests green (9 new: ABBA pattern,
current-point tracking through score/undo, gender codec round-trip, persistence load/save),
build green. Not yet installed on the real TicWatch — on-device check of the setup control and
the divider badge still pending.

---

## 20. Post-v1: "Done" — ending a game and score history (2026-09-11)

Derek asked for a "Done" control on the score card to mark a game over, with a step that
validates the press was intentional before it takes effect, and a "Score history" section on the
new-game screen where completed games are stored.

### Decisions (asked up front, all three answered as recommended)

| Decision | Choice | Why |
|---|---|---|
| Validation | Full-screen confirm — tap Done → a screen shows the final score with End Game/Cancel | Mirrors the app's existing confirm-screen pattern (the old new-game confirm, still used by team-preset/saved-game delete flows) rather than a novel gesture. |
| History detail | Plain list — no per-game detail screen | Matches the app's minimal, one-screen-at-a-time philosophy; nothing needs the full point log a saved game deliberately doesn't keep. |
| History management | Long-press a row to delete, no cap on how many are kept | Same gesture the app already uses for managing team presets (which itself is long-press → a confirm step, not an instant delete — see below); storage cost is trivial, same reasoning as every other codec in this file. |

### What "Done" does

`ScoreViewModel.completeGame()`: archives the game in progress — both teams' identities and the
final score, at whatever point it stood (even 0-0, if that's genuinely how it ended) — as a new
[`SavedGame`](#savedgame-model), then clears the live game's history. The teams and ABBA choice
are *kept*, not reset to the plain defaults: the next screen the UI shows is the new-game setup
screen, which already pre-selects a team by matching it against the current game (section 13's
stickiness for a recurring matchup) — reusing `GameAction.NewGame(current.usTeam, current.themTeam,
current.abbaStart)` for the reset gets that pre-selection for free, so no new reducer branch was
needed. "Confirming Done brings the user back to the new game screen" (as asked) is wired in
WearApp: the confirm screen's `onConfirm` calls `completeGame()` then flips straight to
`showNewGameSetup`.

### `SavedGame` model

New file, `SavedGame.kt`: `id` / `usTeam` / `themTeam` / `usScore` / `themScore` /
`completedAtMillis`. Deliberately *not* the full `ScoreEvent` log — the history section is a plain
list with no detail view (the "history detail" decision above), so nothing today would use a
fuller record, unlike the live game's history which the event log itself already is. `id` is
generated the same way `TeamPreset.id` is (an injectable generator, defaulting to wall-clock
millis) so tests can supply a deterministic sequence and so deleting one saved game can never be
confused with another.

Also in this file: `formatSavedGameTimestamp(millis, zone)` — a pure function (java.time, no
Android import) rendering e.g. "Sep 10, 3:40 PM" for a history row, `zone` defaulting to the real
device zone but overridable so it stays deterministic in JVM tests.

### Persistence

`SavedGameStore` (new interface, same shape as `TeamPresetStore`) — `loadSavedGames()`/
`saveSavedGames(games)`. `DataStoreScoreRepository` now implements all three store interfaces
against the same `game_state` preferences file, one new key (`saved_games`). The codec
(`encodeSavedGames`/`decodeSavedGames`, ScoreRepository.kt) reuses the preset codec's control-
character field/record separators (renamed from `PRESET_FIELD_SEPARATOR`/`PRESET_RECORD_SEPARATOR`
to plain `FIELD_SEPARATOR`/`RECORD_SEPARATOR` now that two codecs share them) — team names in a
saved game go through the same `sanitizeName()` a preset's does, so the same "can never contain the
separator" guarantee applies. Malformed records (wrong field count, blank id, non-numeric
score/timestamp) are dropped, same policy as every other codec in this file.

### UI

- **`DoneButton`** (ScoreScreen.kt): a small accent-coloured pill at `Alignment.CenterEnd` on the
  divider — the mirror position of the existing ABBA `GenderBadge` at `CenterStart`. Unlike the
  badge (informational, no gesture handler, a hold falls through to the zone underneath), this
  needs its own `clickable` — it's an action, and `clickable`'s tap detector wins the same way
  `UndoControl`'s overlay already does, so a hold starting on it doesn't leak through as a score.
- **`EndGameConfirmScreen`** (ScoreScreen.kt): the "validate the press was intentional" step —
  shows the final score with both team names, End Game (accent pill) / Cancel (plain pill),
  styled like the setup screen's own confirm screens.
- **Score history section** (`NewGameSetupScreen.kt`, `PickingScreen`): placed *after*
  Start/Cancel rather than above them, so picking teams and starting a quick game — the common
  path — stays exactly as many scrolls away as it already was. Empty state is a plain "No saved
  games yet." Rows (`SavedGameRow`) show the matchup, final score, and `formatSavedGameTimestamp`;
  sorted newest-first at the display site rather than in storage.
- **Deleting a saved game**: long-pressing a row moves `NewGameSetupScreen`'s existing `SetupMode`
  sealed state to a new `ConfirmingDeleteSavedGame` case, rendering `ConfirmDeleteSavedGameScreen`
  (destructive-red Delete / Cancel, styled like the team-preset `EditingScreen`'s own Delete
  button) — a confirm step, not an instant delete on the long-press itself, matching how deleting
  a team preset already works and because history has nothing else to undo it with, unlike a
  point.

### A real bug found via on-device testing, not just reasoned about: saved games weren't persisting at all

Playing through the feature on the emulator — complete a game, force-stop, relaunch, check score
history — the just-completed game was gone after the restart. Root cause, found by reading the
actual wiring rather than guessing: `rememberScoreViewModel()` (ScoreScreen.kt), the factory that
builds the real production `ScoreViewModel`, constructed it with `historyStore` and `presetStore`
but never passed the new `savedGameStore` parameter — so every `completeGame()`/`deleteSavedGame()`
call updated the in-memory `savedGames` StateFlow correctly (which is why it looked fine right up
until a restart) but the `SavedGameStore?.saveSavedGames(...)` call was silently a no-op against a
`null` store, and nothing ever reached disk. One-line fix: pass `savedGameStore = repository` too.
Re-verified with a clean install (`pm clear`), two full play-through-and-Done cycles, a confirmed
process kill (`pidof` before and after `am force-stop`), and a relaunch — both games' correct
scores and timestamps present, newest first.

While investigating this, also hardened `updateSavedGames`/`updatePresets` against a related but
separate *theoretical* race that was not what actually caused the bug above (the real cause was
the missing wiring, confirmed by reading the code — this is an additional, independently-reasoned
fix, not a re-diagnosis): unlike the live game (`ScoreScreen` doesn't render, so `score()`/`undo()`
aren't reachable, until `ScoreViewModel.isReady` flips true), nothing gates preset/saved-game
interaction on their own DataStore read finishing. A mutation landing before that read completes
would compute its update from the in-memory default-empty state and then persist that shrunk
result, discarding whatever was already on disk. Fixed by tracking each store's initial load as a
`Job` and having `updatePresets`/`updateSavedGames` `join()` it before reading `_presets`/
`_savedGames` — a no-op in the overwhelmingly common case (the load has almost always long finished
by the time a person does anything), but closes the gap for good.

### Verified

`./gradlew :app:testDebugUnitTest :app:assembleDebug` — 88/88 tests green (16 new: 8 saved-game
codec cases in `ScorePersistenceCodecTest` — round trip, empty/blank/malformed input, unknown
colour fallback, a 0-0 result, names containing commas/colons — and 8 in the new
`ScoreViewModelSavedGameTest`, mirroring `ScoreViewModelPresetTest`'s style: archiving captures the
right score/teams/timestamp, completing clears history but keeps teams/ABBA, a 0-0 game still
archives, delete removes the right one and persists the shrunk list, delete of an unknown id is a
no-op, id generation uses the injected generator). Build green.

On the emulator: Done → confirm (shows the real live score) → Cancel returns unchanged; Done →
confirm → End Game lands on the new-game screen with no "score will be lost" warning (history was
already archived) and the just-finished teams pre-selected; the score-history section renders
empty state, then rows after completing games, newest first; long-press a row → delete-confirm →
Cancel keeps it, Delete removes it; the persistence bug above was found and re-verified fixed via
a clean install, two completed games, a confirmed process kill, and a relaunch showing both.

On the real TicWatch Pro 5 Enduro: installed over Derek's actual in-progress game (colour presets
"Flaming Throws" vs "Stallbus", ABBA on) — the Done button renders correctly at the divider's
right edge without colliding with the gender badge on the left or the team colour background, and
the confirm screen renders both team names correctly (wrapping to two lines) against real AMOLED.
Deliberately **not** further verified on the real watch beyond this — completing or deleting
anything would have destroyed Derek's actual real game/history data rather than test data, which
wasn't this session's call to make. Score history section and a full Done→confirm→setup round
trip on real hardware are still open for Derek (or a future session) to check.

---

## 21. Post-v1: score history moved to its own screen (2026-09-11)

Derek asked for score history to be its own page rather than a section embedded in the new-game
screen — the list was appended after Start/Cancel there, which meant every additional completed
game pushed those buttons further from view on a long scroll.

### What changed

- New file, `ScoreHistoryScreen.kt`: the list (or empty state), reached from a single
  `SelectableRow`-styled "Score history" nav row at the same spot the old inline section used to
  start (still after Start/Cancel, same reasoning as before — a quick restart stays exactly as
  many taps away). The row's label includes the count when non-empty (`"Score history (3)"`) so
  there's something to see before tapping in. A "Back" button (same plain-pill style as every
  other secondary action in the app) returns to wherever it was opened from.
- Deletion (long-press a row → confirm screen) moved wholesale into this new file along with
  `SavedGameRow`/`ConfirmDeleteSavedGameScreen` — `NewGameSetupScreen.kt`'s `SetupMode` lost its
  `ConfirmingDeleteSavedGame` case entirely, since that flow has nothing to do with picking teams
  any more.
- `NewGameSetupScreen` no longer takes the full `savedGames: List<SavedGame>` — just
  `savedGamesCount: Int` (for the nav row's label) and an `onViewHistory: () -> Unit` callback,
  so it stays decoupled from the actual saved-game objects.
- `WearApp` (ScoreScreen.kt) gained a `showScoreHistory` boolean alongside its existing
  `showNewGameSetup`/`showEndGameConfirm`, checked *first* in the `when` — rather than nesting it
  inside the `showNewGameSetup` branch or introducing a navigation stack, `showNewGameSetup` just
  stays `true` the whole time the history screen is open on top of it, so turning
  `showScoreHistory` back off via its own Back button falls straight back to
  `NewGameSetupScreen` exactly where setup was left (selections, ABBA choice, scroll position all
  intact) — no stack to manage for what is, today, only ever one level of "on top of".

### Verified

`./gradlew :app:testDebugUnitTest :app:assembleDebug` — still 88/88 green (no test needed to
change; the ViewModel-level behaviour this refactor sits on top of, `completeGame`/
`deleteSavedGame`, is unchanged). On the emulator: the new-game screen's "Score history (2)" row
opens the dedicated screen; long-press → delete-confirm → Delete removes the right game and the
label updates to "Score history (1)" once back; deleting the last one shows the empty state and
the label drops back to plain "Score history"; Back returns to the new-game screen (not the score
card) with everything else on it unchanged.
