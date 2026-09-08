package com.veenstra.ultimatescore

/**
 * The colours a team can be given. Deliberately plain `Long` ARGB rather than Compose's `Color`
 * so this whole model layer stays free of Android/Compose imports and keeps working in plain JVM
 * unit tests — the UI converts to `Color` at the point of use.
 *
 * [swatchArgb] is the true, saturated colour — used both for the pickable dot in the new-game
 * setup screen *and*, via [backgroundArgb], as the actual half-screen background. An earlier
 * version of this used a deep near-black tint for the background instead of the full colour
 * (safer for sunlight contrast and battery), but Derek tried it and preferred true colours — see
 * PLAN.md section 11 "True colours". [textColorArgbFor] is what keeps that legible: several of
 * these swatches (GRAY, ORANGE, ...) are light enough that white numerals would fail contrast on
 * them, so the numeral/label colour is computed per background rather than assumed to be white.
 */
enum class TeamColor(val swatchArgb: Long) {
    /** No colour chosen — the pure-black background the app had before team colours existed. */
    NONE(0xFF2A2A2A),
    /**
     * True white — added in place of PURPLE (2026-09-07) and ordered right after [NONE] so the
     * two-per-row picker grid (see `ColorSwatches` in NewGameSetupScreen.kt) puts white and
     * black-ish [NONE] next to each other as the first pair.
     */
    WHITE(0xFFFFFFFF),
    PINK(0xFFE91E63),
    GRAY(0xFF9E9E9E),
    BLUE(0xFF2196F3),
    GREEN(0xFF4CAF50),
    ORANGE(0xFFFF9800),
    RED(0xFFF44336),
}

/**
 * The half-screen background for this colour. [TeamColor.NONE] stays pure black — the unchanged
 * v1 look — every other colour is its true, full-saturation swatch (see [TeamColor]'s doc).
 */
val TeamColor.backgroundArgb: Long
    get() = if (this == TeamColor.NONE) 0xFF000000 else swatchArgb

/**
 * WCAG relative luminance of a plain ARGB colour (the same math browsers use for accessible-
 * contrast checks). A pure function on `Long` so it — and [contrastRatio], [textColorArgbFor] —
 * are covered by JVM unit tests with no Android/Compose dependency.
 */
fun relativeLuminance(argb: Long): Double {
    fun linearize(channel: Long): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    val r = (argb shr 16) and 0xFFL
    val g = (argb shr 8) and 0xFFL
    val b = argb and 0xFFL
    return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
}

/** WCAG contrast ratio between two colours — from 1:1 (identical) to 21:1 (black vs white). */
fun contrastRatio(argbA: Long, argbB: Long): Double {
    val lighter = maxOf(relativeLuminance(argbA), relativeLuminance(argbB))
    val darker = minOf(relativeLuminance(argbA), relativeLuminance(argbB))
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * The higher-contrast of black or white against [backgroundArgb]. Needed because true-colour
 * backgrounds (see [TeamColor]) are sometimes light enough that white text would fail — GRAY and
 * ORANGE, for instance, contrast better with black than white by a wide margin.
 */
fun textColorArgbFor(backgroundArgb: Long): Long {
    val contrastWithWhite = contrastRatio(backgroundArgb, 0xFFFFFFFF)
    val contrastWithBlack = contrastRatio(backgroundArgb, 0xFF000000)
    return if (contrastWithWhite >= contrastWithBlack) 0xFFFFFFFF else 0xFF000000
}

/**
 * Trims whitespace and drops control characters (tabs, newlines, and the rest of the U+0000
 * to U+001F range, plus U+007F). Every user-typed team/preset name goes through this before it's
 * stored anywhere, for one concrete reason: [encodePresets]/[decodePresets] (ScoreRepository)
 * use two of those control characters as field/record separators so preset names can safely
 * contain the punctuation ordinary users actually type — commas, colons — without a hand-rolled
 * escaping scheme. A name that itself contained a separator character would corrupt that codec's
 * framing, so it's simplest to guarantee up front that names never contain any control character
 * at all, rather than escape just the two that matter.
 */
internal fun sanitizeName(raw: String?): String = raw.orEmpty().filterNot { it.isISOControl() }.trim()

/** A team's identity for one game: what it's called and what colour its half of the screen is. */
data class TeamConfig(
    val name: String,
    val color: TeamColor = TeamColor.NONE,
) {
    companion object {
        val DEFAULT_US = TeamConfig("US", TeamColor.NONE)
        val DEFAULT_THEM = TeamConfig("THEM", TeamColor.NONE)

        /** Blank/whitespace names fall back to the default rather than rendering an empty label. */
        fun named(name: String?, color: TeamColor, fallback: TeamConfig): TeamConfig {
            val sanitized = sanitizeName(name)
            return TeamConfig(
                name = sanitized.ifEmpty { fallback.name },
                color = color,
            )
        }
    }
}

/**
 * A user-created, watch-local, persisted team identity — see PLAN.md section 13. Distinct from
 * [TeamConfig] (which is just "this game's" name+colour, unchanged since section 11) because a
 * saved preset needs a stable identity of its own: [id] is what rename/recolour/delete and the
 * setup screen's selection act on, so renaming a preset (or two presets sharing a name) never
 * gets confused with picking a different one — the bug the old US_PRESETS name-matching had.
 *
 * [id] is generated at creation time by the caller (ScoreViewModel), not here, so tests can
 * supply a deterministic generator instead of the real wall-clock-millis one.
 */
data class TeamPreset(
    val id: String,
    val name: String,
    val color: TeamColor = TeamColor.NONE,
) {
    /** What this preset becomes for the running game once picked in the setup screen. */
    fun toConfig(): TeamConfig = TeamConfig(name, color)
}

/** Which independently-managed preset list an action or preset belongs to — see PLAN.md section 13. */
enum class PresetGroup { MY_TEAMS, OPPONENTS }

/**
 * Both preset lists together, as exposed by [ScoreViewModel.presets] and round-tripped by
 * [TeamPresetStore]. Two separate lists rather than one tagged list because "my teams" and
 * "opponents" are decided to be independent by design (PLAN.md section 13) — there's no shared
 * numbering or ordering between them.
 */
data class TeamPresetLists(
    val myTeams: List<TeamPreset> = emptyList(),
    val opponents: List<TeamPreset> = emptyList(),
) {
    fun forGroup(group: PresetGroup): List<TeamPreset> = when (group) {
        PresetGroup.MY_TEAMS -> myTeams
        PresetGroup.OPPONENTS -> opponents
    }

    fun withGroup(group: PresetGroup, presets: List<TeamPreset>): TeamPresetLists = when (group) {
        PresetGroup.MY_TEAMS -> copy(myTeams = presets)
        PresetGroup.OPPONENTS -> copy(opponents = presets)
    }
}
