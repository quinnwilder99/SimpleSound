package com.simplesound.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

/**
 * A dark, cinematic backdrop: a soft radial glow of [accent] rising from the
 * bottom-center of the screen, fading upward into true black by roughly the top
 * third. Drawn behind the app's content to give the dark theme depth without
 * sacrificing readability.
 *
 * @param accent the user's chosen accent color; used as the glow's hue.
 * @param intensity how opaque the glow is at its brightest point (0..1).
 */
@Composable
fun GlowBackground(
    accent: Color,
    modifier: Modifier = Modifier,
    intensity: Float = 0.55f
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val center = Offset(x = w / 2f, y = h * 1.05f)
                val radius = h * 1.25f
                drawRect(color = Color.Black)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = intensity),
                            accent.copy(alpha = intensity * 0.35f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius,
                        tileMode = TileMode.Clamp
                    )
                )
            }
    )
}
