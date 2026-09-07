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
    PINK(0xFFE91E63),
    GRAY(0xFF9E9E9E),
    BLUE(0xFF2196F3),
    GREEN(0xFF4CAF50),
    ORANGE(0xFFFF9800),
    PURPLE(0xFF9C27B0),
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

/** A team's identity for one game: what it's called and what colour its half of the screen is. */
data class TeamConfig(
    val name: String,
    val color: TeamColor = TeamColor.NONE,
) {
    companion object {
        val DEFAULT_US = TeamConfig("US", TeamColor.NONE)
        val DEFAULT_THEM = TeamConfig("THEM", TeamColor.NONE)

        /**
         * Pickable identities for your own team. Choosing one sets the name *and* its colour;
         * the colour can still be overridden afterwards in the setup screen.
         */
        val US_PRESETS = listOf(
            DEFAULT_US,
            TeamConfig("Flaming Nipples", TeamColor.PINK),
            TeamConfig("Flaming Throws", TeamColor.GRAY),
        )

        /** Blank/whitespace names fall back to the default rather than rendering an empty label. */
        fun named(name: String?, color: TeamColor, fallback: TeamConfig): TeamConfig {
            val trimmed = name?.trim().orEmpty()
            return TeamConfig(
                name = trimmed.ifEmpty { fallback.name },
                color = color,
            )
        }
    }
}
