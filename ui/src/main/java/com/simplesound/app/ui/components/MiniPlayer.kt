package com.simplesound.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.ui.LocalPlayer

/**
 * Persistent now-playing bar pinned above the tab bar. Always visible once a
 * track has been played at least once; it keeps showing the last played track
 * title even when playback is stopped. Mirrors Samsung Music's mini player:
 * the track's own artwork thumbnail, title over artist, three transport
 * controls (reverse / play-stop / skip), and a thin progress line along the
 * very bottom edge that fills as the track plays.
 *
 * The bar is rendered in a "liquid glass" style reminiscent of Apple's design
 * language: a translucent frosted surface with a soft specular highlight along
 * the top edge and a thin bright-to-dim border so it reads as a glass capsule
 * floating above the dark content beneath. The middle button serves as both
 * play and stop: when playing it pauses, when paused it resumes. Tapping
 * anywhere else on the bar opens the full Now Playing screen for that track.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val player = LocalPlayer.current
    val track by player.currentTrack.collectAsStateWithLifecycle()
    val lastPlayed by player.lastPlayedTrack.collectAsStateWithLifecycle()
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val positionMs by player.positionMs.collectAsStateWithLifecycle()
    val durationMs by player.durationMs.collectAsStateWithLifecycle()

    // Prefer the currently playing track, fall back to the last played track so
    // the bar stays visible after stop() clears the current track.
    val display = track ?: lastPlayed ?: return
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // Liquid-glass palette. The surface is a frosted translucent layer; a
    // top-down specular gradient adds the "wet" highlight that defines the glass
    // look. Colors are tuned for the dark-only theme. The body alpha is kept
    // high so the bar is effectively opaque — content behind it is fully
    // obscured, giving the glass a solid frosted backing rather than a
    // see-through film.
    val glassTint = Color(0xFF1A1C20)
    val glassHighlight = Color.White.copy(alpha = 0.18f)
    val glassEdge = Color.White.copy(alpha = 0.22f)
    val glassEdgeBottom = Color.White.copy(alpha = 0.05f)
    val iconTint = Color.White.copy(alpha = 0.92f)
    val textTint = Color.White.copy(alpha = 0.95f)
    val subTextTint = Color.White.copy(alpha = 0.65f)
    val progressTint = MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(glassTint.copy(alpha = 1.0f))
                // Specular top highlight: a thin bright band along the upper rim
                // that sells the "liquid" sheen, fading to transparent at mid-height.
                .drawBehind {
                    val h = size.height
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(glassHighlight, Color.Transparent),
                                startY = 0f,
                                endY = h * 0.5f,
                            ),
                    )
                }
                // Glass rim: bright at the top, nearly invisible at the bottom — the
                // signature edge light of a glass capsule.
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(colors = listOf(glassEdge, glassEdgeBottom)),
                    shape = RoundedCornerShape(28.dp),
                )
                .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // embeddedSource = display.uri so Artwork decodes the per-track
            // embedded picture (ID3 APIC) first, same order TrackRow/NowPlaying
            // follow; display.albumArtUri is the album-level fallback.
            Artwork(
                uri = display.albumArtUri,
                embeddedSource = display.uri,
                modifier = Modifier.size(44.dp),
                corner = 10.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = display.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = textTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = display.artistOrUnknown,
                    style = MaterialTheme.typography.bodySmall,
                    color = subTextTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Transport controls: reverse (previous), play/stop (toggle), skip (next).
            IconButton(onClick = { player.previous() }) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = iconTint)
            }
            IconButton(onClick = { player.togglePlayPause() }) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = iconTint,
                )
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = iconTint)
            }
        }

        // Thin progress line along the bottom edge, Samsung Music-style; only
        // shown once a duration is actually known so it doesn't flash at 0%
        // before the first track ever loads.
        if (durationMs > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(2.5.dp)
                        .background(progressTint),
            )
        }
    }
}
