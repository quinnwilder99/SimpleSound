package com.simplesound.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.simplesound.core.theme.AccentColor

/**
 * simpleSOUND theme. Dark is the ONLY theme — [isSystemInDarkTheme] is ignored
 * on purpose so the app never renders a white/light surface. The single knob a
 * user has is the [accent] color, which drives primary highlights (active tab,
 * toggles, play button, headers).
 */
@Composable
fun SimpleSoundTheme(
    accent: AccentColor = AccentColor.Default,
    content: @Composable () -> Unit
) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // referenced intentionally; result discarded (always dark)

    val accentColor = accent.color
    val onAccent = if (accentColor.luminance() > 0.5f) Color.Black else Color.White

    val colorScheme = darkColorScheme(
        primary = accentColor,
        onPrimary = onAccent,
        primaryContainer = accentColor.copy(alpha = 0.18f),
        onPrimaryContainer = accentColor,
        secondary = accentColor,
        background = SoundColors.Background,
        onBackground = SoundColors.OnBackground,
        surface = SoundColors.Surface,
        onSurface = SoundColors.OnBackground,
        surfaceVariant = SoundColors.SurfaceVariant,
        onSurfaceVariant = SoundColors.OnSurfaceMuted,
        outline = SoundColors.Divider,
        outlineVariant = SoundColors.Divider
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SoundColors.Background.value.toInt()
            window.navigationBarColor = SoundColors.Background.value.toInt()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SoundTypography,
        content = content
    )
}
