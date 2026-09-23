package com.simplesound.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplesound.app.data.model.Track

/**
 * Now-Playing queue sheet.
 *
 * Shows the *temp* queue (the ordered list of tracks currently loaded into the
 * player) along with a header describing where this queue came from
 * ([queueTitle], e.g. the playlist name, "All tracks", "Queue", or
 * "Search results"). The user can:
 *   - tap a row to jump to that track (without rebuilding the queue),
 *   - move a row up/down to reorder the temp queue (the source playlist /
 *     all-tracks list is NOT modified),
 *   - remove a row from the temp queue.
 *
 * The currently playing row is highlighted via [currentIndex].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    queueTitle: String,
    onPlay: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val title = queueTitle.ifBlank { "Queue" }

    // A move/remove row-icon click closure captures `index` from this composition;
    // Compose doesn't recompose synchronously on click, so a rapid double-tap can
    // fire a second time with the same now-stale index (whatever track shifted into
    // that slot after the first tap gets acted on too). Disabling every row's
    // move/remove button until `queue` itself changes -- confirming the in-flight
    // action actually landed -- closes that window without needing PlayerController's
    // index-based API to change.
    var actionInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(queue) { actionInFlight = false }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
        ) {
            // Header: context this queue came from + count.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val countText =
                        if (queue.isEmpty()) {
                            "Empty"
                        } else {
                            val pos = (currentIndex + 1).coerceIn(1, queue.size)
                            "$pos of ${queue.size}"
                        }
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            if (queue.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No tracks in queue",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
                        val isCurrent = index == currentIndex
                        QueueRow(
                            track = track,
                            isCurrent = isCurrent,
                            canMoveUp = index > 0 && !actionInFlight,
                            canMoveDown = index < queue.lastIndex && !actionInFlight,
                            canRemove = !actionInFlight,
                            onPlay = { onPlay(index) },
                            onMoveUp = {
                                actionInFlight = true
                                onMove(index, index - 1)
                            },
                            onMoveDown = {
                                actionInFlight = true
                                onMove(index, index + 1)
                            },
                            onRemove = {
                                actionInFlight = true
                                onRemove(index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork / playing indicator
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Artwork(
                uri = track.albumArtUri,
                embeddedSource = track.uri,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.width(12.dp))

        // Title + artist (tap to play)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistOrUnknown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Play-on-tap affordance via the title block
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = "Play ${track.title}",
                tint =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "Move up",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = "Move down",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
