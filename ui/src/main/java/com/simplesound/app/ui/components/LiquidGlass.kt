package com.simplesound.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Liquid glass" — a frosted, translucent capsule evocative of Apple's Liquid
 * Glass material. Pure-Compose implementation (no RenderEffect blur required)
 * drawn in [drawBehind]:
 *
 *  1. A vertical body gradient: bright translucent white at the top fading to a
 *     deep, dim translucent grey at the bottom — the hallmark sheen of glass.
 *  2. A soft accent-tinted radial "gloss" pooled near the top edge, so the glass
 *     catches the radiant glow behind it.
 *  3. A specular diagonal streak — a thin skewed highlight arcing across the
 *     upper third, the detail that reads as wet, specular light.
 *
 * Apply to any container; best over the [GlowBackground] so refraction of the
 * radiant accent shows through.
 *
 * @param corner radius of the glass capsule. Defaults to a pill-ish 22dp.
 * @param tint optional accent hue mixed into the top gloss; defaults to the
 *             Material primary (accent) so the glass matches the user's theme.
 * @param bodyAlpha peak opacity of the glass body (0..1). Lower = more see-through.
 */
fun Modifier.liquidGlass(
    corner: Dp = 22.dp,
    tint: Color = Color.Unspecified,
    bodyAlpha: Float = 0.10f
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val accent = if (tint == Color.Unspecified) Color.White else tint
        val bodyTop = Color.White.copy(alpha = bodyAlpha * 1.6f)
        val bodyBottom = Color.Black.copy(alpha = bodyAlpha * 1.2f)

        // 1) Body — vertical sheen of the glass.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(bodyTop, bodyBottom),
                startY = 0f,
                endY = size.height
            )
        )

        // 2) Pooled gloss near the top, tinted by the accent so the glass
        //    refracts the radiant glow behind it.
        val glossCenter = Offset(x = size.width * 0.5f, y = size.height * 0.18f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = bodyAlpha * 1.1f),
                    Color.Transparent
                ),
                center = glossCenter,
                radius = size.minDimension * 0.9f,
                tileMode = TileMode.Clamp
            )
        )

        // 3) Specular diagonal streak across the upper third — the wet highlight.
        val streakTop = Offset(x = size.width * 0.12f, y = size.height * 0.04f)
        val streakEnd = Offset(x = size.width * 0.78f, y = size.height * 0.34f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    Color.White.copy(alpha = bodyAlpha * 2.2f),
                    Color.White.copy(alpha = 0f)
                ),
                start = streakTop,
                end = streakEnd,
                tileMode = TileMode.Clamp
            )
        )
    }
    // Soft inner rim via a translucent white hairline border (laid over content).
    .border(
        width = 0.75.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.08f)
            )
        ),
        shape = RoundedCornerShape(corner)
    )

/**
 * A tinted drop shadow painted behind a glass surface to give it lift without
 * a real blur — a faint, soft accent halo so the glass appears to float over
 * the radiant background. Pair with [liquidGlass].
 */
fun Modifier.glassShadow(
    tint: Color = Color.Unspecified,
    spread: Dp = 18.dp
): Modifier = this.drawBehind {
    if (tint == Color.Unspecified) return@drawBehind
    val px = spread.toPx()
    drawRoundRect(
        color = tint.copy(alpha = 0.18f),
        topLeft = Offset(x = -px, y = -px * 0.5f),
        size = androidx.compose.ui.geometry.Size(
            width = size.width + px * 2,
            height = size.height + px
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(px, px),
        alpha = 0.6f
    )
}

/**
 * Convenience wrapper: a [Box] pre-skinned with liquid glass over the radiant
 * background. Use for glass cards whose children should sit above the sheen.
 */
@Composable
fun LiquidGlassBox(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    bodyAlpha: Float = 0.10f,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .liquidGlass(
                corner = corner,
                tint = MaterialTheme.colorScheme.primary,
                bodyAlpha = bodyAlpha
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

// Suppress unused-import warnings for helpers kept for future tweaking.
@Suppress("unused")
private fun Modifier.noop(): Modifier = this