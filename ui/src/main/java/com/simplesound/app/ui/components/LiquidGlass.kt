package com.simplesound.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
 *  1. A vertical body gradient: a soft, smooth white→grey→dark fade top to
 *     bottom — the quiet sheen of glass, blended across three stops so it
 *     reads as a diffuse, even wash rather than a stripey highlight.
 *  2. A soft accent-tinted radial "gloss" pooled near the top edge, so the glass
 *     catches the radiant glow behind it.
 *  3. A faint inner rim border.
 *
 * There is deliberately no specular streak / "lightning" highlight — the glass
 * is smooth and diffuse throughout, so it never catches a hard glare mid-card.
 *
 * Apply to any container; best over the [GlowBackground] so refraction of the
 * radiant accent shows through.
 *
 * Use with restraint. Liquid glass reads best as an occasional "hero" material
 * — a persistent bar, a detail header, a selected row — not as the default
 * skin for every row in a scrolling list. Stack the full effect (gloss + rim)
 * on dozens of consecutive items and the individual glints disappear into a
 * busy field of hairlines; nothing stands out because everything is glass.
 * For repeated rows/cards, keep [showGloss] and [showRim] off and lean on a
 * quieter flat [bodyAlpha] wash instead, reserving the full treatment for the
 * one or two surfaces per screen that should actually draw the eye.
 *
 * @param corner radius of the glass capsule. Defaults to a pill-ish 22dp.
 * @param tint optional accent hue mixed into the top gloss; defaults to the
 *             Material primary (accent) so the glass matches the user's theme.
 * @param bodyAlpha peak opacity of the glass body (0..1). Lower = more see-through.
 * @param showGloss whether to pool the accent-tinted gloss near the top edge.
 *                   Reserve this for the one or two surfaces per screen that
 *                   should read as "the" glass moment (a persistent bar, a
 *                   detail header). Turn it off for anything repeated in a
 *                   list or grid — a gloss on every row stacks into visual
 *                   noise instead of depth.
 * @param showRim whether to draw the translucent hairline border. Same
 *                reasoning as [showGloss]: a rim on every item in a long list
 *                reads as a grid of cells, not glass. Keep it for standalone
 *                or hero surfaces, drop it for repeated rows/cards.
 */
fun Modifier.liquidGlass(
    corner: Dp = 22.dp,
    tint: Color = Color.Unspecified,
    bodyAlpha: Float = 0.10f,
    showGloss: Boolean = true,
    showRim: Boolean = true,
): Modifier =
    this
        .clip(RoundedCornerShape(corner))
        .drawBehind {
            val accent = if (tint == Color.Unspecified) Color.White else tint
            // Gentle, low-contrast body. A smooth three-stop fade (bright top → mid
            // neutral → dim bottom) keeps the glass even and diffuse, with no hard
            // band of light in the middle.
            val bodyTop = Color.White.copy(alpha = bodyAlpha * 1.15f)
            val bodyMid = Color.White.copy(alpha = bodyAlpha * 0.45f)
            val bodyBottom = Color.Black.copy(alpha = bodyAlpha * 0.9f)

            // 1) Body — smooth vertical sheen of the glass.
            drawRect(
                brush =
                    Brush.verticalGradient(
                        colors = listOf(bodyTop, bodyMid, bodyBottom),
                        startY = 0f,
                        endY = size.height,
                    ),
            )

            // 2) Pooled gloss near the top, tinted by the accent so the glass
            //    refracts the radiant glow behind it. Kept soft and wide so it pools
            //    gently near the top edge rather than glaring. Opt-in via
            //    [showGloss] — see its doc for why repeated rows skip this.
            if (showGloss) {
                val glossCenter = Offset(x = size.width * 0.5f, y = size.height * 0.20f)
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    accent.copy(alpha = bodyAlpha * 0.8f),
                                    Color.Transparent,
                                ),
                            center = glossCenter,
                            radius = size.minDimension * 0.95f,
                            tileMode = TileMode.Clamp,
                        ),
                )
            }
        }
        // Soft inner rim via a translucent white hairline border (laid over content).
        // Lowered top stop so the rim is a faint suggestion, not a bright edge.
        // Opt-in via [showRim] — see its doc for why repeated rows skip this.
        .let { mod ->
            if (showRim) {
                mod.border(
                    width = 0.75.dp,
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.White.copy(alpha = 0.28f),
                                    Color.White.copy(alpha = 0.06f),
                                ),
                        ),
                    shape = RoundedCornerShape(corner),
                )
            } else {
                mod
            }
        }

/**
 * A tinted drop shadow painted behind a glass surface to give it lift without
 * a real blur — a faint, soft accent halo so the glass appears to float over
 * the radiant background. Pair with [liquidGlass].
 */
fun Modifier.glassShadow(
    tint: Color = Color.Unspecified,
    spread: Dp = 18.dp,
): Modifier =
    this.drawBehind {
        if (tint == Color.Unspecified) return@drawBehind
        val px = spread.toPx()
        drawRoundRect(
            color = tint.copy(alpha = 0.18f),
            topLeft = Offset(x = -px, y = -px * 0.5f),
            size =
                androidx.compose.ui.geometry.Size(
                    width = size.width + px * 2,
                    height = size.height + px,
                ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(px, px),
            alpha = 0.6f,
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
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .liquidGlass(
                    corner = corner,
                    tint = MaterialTheme.colorScheme.primary,
                    bodyAlpha = bodyAlpha,
                ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

// Suppress unused-import warnings for helpers kept for future tweaking.
@Suppress("unused")
private fun Modifier.noop(): Modifier = this
