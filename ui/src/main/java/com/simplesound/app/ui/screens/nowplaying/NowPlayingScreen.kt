package com.simplesound.app.ui.screens.nowplaying

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.components.DeleteTrackDialog
import com.simplesound.app.ui.components.TrackActionsSheet
import com.simplesound.app.ui.components.QueueSheet
import com.simplesound.app.ui.components.TrackDetailsDialog
import java.io.File

/**
 * Full-screen now-playing view. Opens when a track is tapped or when the mini-player
 * is pressed. Redesigned layout (top to bottom):
 *  1. Action bar (down arrow / speed / more)
 *  2. Album art + track metadata (title / artist)
 *  3. Mid-tier utility row (queue / favorite / add to playlist)
 *  4. Scrubber + timestamps
 *  5. Playback controls (shuffle / prev / play-pause / next / repeat)
 */
@Composable
fun NowPlayingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val track by player.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    val positionMs by player.positionMs.collectAsStateWithLifecycle()
    val durationMs by player.durationMs.collectAsStateWithLifecycle()
    val isShuffleOn by player.isShuffleOn.collectAsStateWithLifecycle()
    val repeatMode by player.repeatMode.collectAsStateWithLifecycle()
    val playbackSpeed by player.playbackSpeed.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showActionsSheet by remember { mutableStateOf(false) }
    var deleteTrack by remember { mutableStateOf(false) }
    var detailsTrack by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var seekingValue by remember { mutableStateOf<Float?>(null) }
    // ---- Temp queue (Now Playing queue sheet) ----
    val queue by player.queue.collectAsStateWithLifecycle()
    val queueIndex by player.queueIndex.collectAsStateWithLifecycle()
    val queueTitle by player.queueTitle.collectAsStateWithLifecycle()
    var showQueueSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1 ACTION BAR
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = "Minimize",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSpeedSheet = true }) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = "Playback speed",
                        tint = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(onClick = { showActionsSheet = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            val current = track
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.weight(0.6f))
                // 2 ALBUM ART + METADATA
                // Pass the track's MediaStore URI as embeddedSource so Artwork
                // decodes the per-track embedded picture (ID3 APIC) first, just
                // like TrackRow does; track.albumArtUri is the album-level fallback.
                Artwork(
                    uri = current.albumArtUri,
                    embeddedSource = current.uri,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    corner = 24.dp,
                    iconSize = 96.dp
                )
                Spacer(Modifier.weight(0.4f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = current.artistOrUnknown,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(20.dp))

                // 3 MID-TIER UTILITY ROW
                val isFavorite = current.id in favoriteIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = "Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(onClick = { vm.toggleFavoriteTrack(current.id) }) {
                        Icon(
                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Add to playlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 4 PROGRESS BAR + TIMELINE
                val duration = durationMs.coerceAtLeast(0L)
                val pos = seekingValue?.let { (it * duration).toLong() }
                    ?: positionMs.coerceIn(0L, duration)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatClock(pos),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = if (duration > 0) pos.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { v -> seekingValue = v },
                        onValueChangeFinished = {
                            seekingValue?.let { v ->
                                val target = (v * duration).toLong()
                                player.seekTo(target)
                            }
                            seekingValue = null
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                    Text(
                        text = formatClock(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 5 PLAYBACK CONTROLS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { player.toggleShuffle() }) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { player.previous() }) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { player.togglePlayPause() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    IconButton(onClick = { player.next() }) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = { player.cycleRepeatMode() }) {
                        Icon(
                            if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- Dialogs / sheets ----
    val current = track
    if (showAddDialog && current != null) {
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onPick = { pl -> vm.addTracksToPlaylist(pl.id, listOf(current.id)); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }
    if (showActionsSheet && current != null) {
        TrackActionsSheet(
            track = current,
            isFavorite = current.id in favoriteIds,
            onPlay = { player.playSingle(current) },
            onToggleFavorite = { vm.toggleFavoriteTrack(current.id) },
            onAddToPlaylist = { showActionsSheet = false; showAddDialog = true },
            onDelete = { showActionsSheet = false; deleteTrack = true },
            onShare = { shareTrack(context, current) },
            onDetails = { showActionsSheet = false; detailsTrack = true },
            onDismiss = { showActionsSheet = false }
        )
    }
    if (deleteTrack && current != null) {
        DeleteTrackDialog(
            track = current,
            onConfirm = { vm.deleteTrack(current.id); deleteTrack = false; onBack() },
            onDismiss = { deleteTrack = false }
        )
    }
    if (detailsTrack && current != null) {
        TrackDetailsDialog(track = current, onDismiss = { detailsTrack = false })
    }
    if (showSpeedSheet) {
        PlaybackSpeedSheet(
            currentSpeed = playbackSpeed,
            onSpeedChange = { player.setSpeed(it) },
            onDismiss = { showSpeedSheet = false }
        )
    }
    if (showQueueSheet) {
        QueueSheet(
            queue = queue,
            currentIndex = queueIndex,
            queueTitle = queueTitle,
            onPlay = { index -> player.playQueueItemAt(index) },
            onMove = { from, to -> player.moveQueueItem(from, to) },
            onRemove = { index -> player.removeQueueItem(index) },
            onDismiss = { showQueueSheet = false }
        )
    }
}

/**
 * Bottom sheet for adjusting playback speed (0.1x to 2.0x). A slider is paired with
 * preset chips for quick jumps; 1.0x is highlighted and starts the row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Playback speed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "%.1fx".format(currentSpeed),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (currentSpeed == 1.0f) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            val presets = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = (currentSpeed - preset).let { it >= -0.001f && it <= 0.001f }
                    AssistChip(
                        onClick = { onSpeedChange(preset) },
                        label = { Text("%.2fx".format(preset)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Slider(
                value = currentSpeed,
                onValueChange = { onSpeedChange(it) },
                valueRange = 0.1f..2.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onSpeedChange(1.0f) }) {
                    Text("Reset to 1.0x")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** mm:ss formatting for the timeline. */
private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** Share a track's file via Android's share sheet. */
private fun shareTrack(context: android.content.Context, track: com.simplesound.app.data.model.Track) {
    val path = track.uri.removePrefix("file://")
    val file = File(path)
    if (file.exists()) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share \"${track.title}\""))
    }
}