package com.simplesound.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.simplesound.core.theme.AccentColor

/**
 * simpleSOUND is dark-only. These are the shared neutral surfaces; the accent
 * (primary) color is user-selectable and injected at runtime — see [AccentColor]
 * (in :core) and [SimpleSoundTheme].
 */
object SoundColors {
    val Background = Color(0xFF000000)      // true black, like the screenshots
    val Surface = Color(0xFF16181C)         // cards / list containers
    val SurfaceVariant = Color(0xFF23262B)  // pressed / secondary chips
    val OnBackground = Color(0xFFF4F5F7)
    val OnSurfaceMuted = Color(0xFF9AA0A6)  // secondary text (artist, counts)
    val Divider = Color(0xFF2A2D31)
    val MiniPlayer = Color(0xFF4A1F2E)      // maroon now-playing bar
}

/**
 * UI-side mapping from the pure [AccentColor] enum (in :core) to a Compose [Color].
 * Keeping the palette here means :core stays free of any Compose dependency while
 * :data / :playback can still reference the enum identifier.
 *
 * For [AccentColor.GradientGrey] this returns the leading (brighter) stop so the
 * material colorScheme.primary still has a single sane value; the full gradient is
 * exposed via [AccentColor.gradient].
 */
val AccentColor.color: Color
    get() = when (this) {
        AccentColor.GradientGrey -> Color(0xFFB8BFC8)
        AccentColor.Black        -> Color(0xFF000000)
        AccentColor.Graphite     -> Color(0xFF3A3D44)
        AccentColor.Silver       -> Color(0xFF9AA0A6)
        AccentColor.White        -> Color(0xFFF4F5F7)
        AccentColor.Teal         -> Color(0xFF2DD4C4)
        AccentColor.Violet       -> Color(0xFF9B8CFF)
        AccentColor.Coral        -> Color(0xFFFF6F61)
        AccentColor.Amber        -> Color(0xFFFFB74D)
        AccentColor.Rose         -> Color(0xFFFF7EB6)
        AccentColor.Lime         -> Color(0xFFA3E635)
        AccentColor.Sky          -> Color(0xFF4FC3F7)
        AccentColor.Sand         -> Color(0xFFD7C9AA)
    }

/**
 * The optional secondary stop for accents that are rendered as a gradient.
 * Returns null for solid accents (so callers know to render a flat color).
 */
val AccentColor.gradientEnd: Color?
    get() = when (this) {
        AccentColor.GradientGrey -> Color(0xFF3A3D44)
        else -> null
    }

/** True when this accent should be rendered as a two-stop gradient. */
val AccentColor.isGradient: Boolean
    get() = gradientEnd != null

/**
 * A horizontal [Brush] for gradient accents, or null for solid ones. Useful for
 * swatch previews and any surface that wants to show the full gradient.
 */
val AccentColor.gradient: Brush?
    get() = gradientEnd?.let { end ->
        Brush.horizontalGradient(colors = listOf(color, end))
    }

/** Human-readable label for the accent, shown in the picker. */
val AccentColor.label: String
    get() = when (this) {
        AccentColor.GradientGrey -> "Gradient grey"
        AccentColor.Black   -> "Black"
        AccentColor.Graphite -> "Graphite"
        AccentColor.Silver  -> "Silver"
        AccentColor.White   -> "White"
        else -> name
    }