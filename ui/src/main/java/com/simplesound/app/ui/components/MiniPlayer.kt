package com.simplesound.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.theme.SoundColors

/**
 * Persistent now-playing bar pinned above the tab bar, matching the maroon pill
 * in the reference screenshots. Always visible once a track has been played at
 * least once; it keeps showing the last played track title even when playback is
 * stopped. Shows only the track title plus three transport controls: reverse
 * (previous), play/stop (toggle), and skip (next).
 *
 * The middle button serves as both play and stop: when playing it pauses, when
 * paused it resumes. Tapping anywhere else on the bar opens the full Now Playing
 * screen for that track.
 */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val player = LocalPlayer.current
    val track by player.currentTrack.collectAsStateWithLifecycle()
    val lastPlayed by player.lastPlayedTrack.collectAsStateWithLifecycle()
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()

    // Prefer the currently playing track, fall back to the last played track so
    // the bar stays visible after stop() clears the current track.
    val display = track ?: lastPlayed ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(SoundColors.MiniPlayer)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = display.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Transport controls: reverse (previous), play/stop (toggle), skip (next).
        IconButton(onClick = { player.previous() }) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White)
        }
        IconButton(onClick = { player.togglePlayPause() }) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White
            )
        }
        IconButton(onClick = { player.next() }) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White)
        }
    }
}