package com.simplesound.app.ui.theme

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
 */
val AccentColor.color: Color
    get() = when (this) {
        AccentColor.Teal   -> Color(0xFF2DD4C4)
        AccentColor.Violet -> Color(0xFF9B8CFF)
        AccentColor.Coral  -> Color(0xFFFF6F61)
        AccentColor.Amber  -> Color(0xFFFFB74D)
        AccentColor.Rose   -> Color(0xFFFF7EB6)
        AccentColor.Lime   -> Color(0xFFA3E635)
        AccentColor.Sky    -> Color(0xFF4FC3F7)
        AccentColor.Sand   -> Color(0xFFD7C9AA)
    }

/** Human-readable label for the accent, shown in the picker. */
val AccentColor.label: String
    get() = name
