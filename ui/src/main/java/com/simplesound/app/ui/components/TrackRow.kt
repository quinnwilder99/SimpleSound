package com.simplesound.app.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplesound.app.data.model.Track

/**
 * A single track list row: artwork, title + artist, overflow menu.
 *
 * When [selectionMode] is true the row shows a selection indicator instead of the
 * overflow button and toggles selection on tap (rather than playing). A long-press
 * enters selection mode via [onLongClick].
 *
 * When both [selectionMode] and [customOrderMode] are true the row shows up/down
 * arrows on the right so the user can reorder the track within the playlist.
 * Reordering is only permitted while the selection bar is present — the arrows
 * never appear outside that combined mode.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    customOrderMode: Boolean = false,
    canMoveUp: Boolean = true,
    canMoveDown: Boolean = true,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
            } else {
                // embeddedSource = track.uri so Artwork decodes the per-track
                // embedded picture (ID3 APIC) first; track.albumArtUri is the
                // album-level fallback when no embedded art exists.
                Artwork(
                    uri = track.albumArtUri,
                    embeddedSource = track.uri,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistOrUnknown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            // Custom-order reordering is only permitted while the selection bar
            // is present; otherwise fall through to the normal overflow button.
            selectionMode && customOrderMode -> {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Move up",
                            tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = "Move down",
                            tint = if (canMoveDown) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            selectionMode -> {
                // Hide overflow/reorder buttons while selecting; keep spacing consistent.
                Box(Modifier.size(48.dp))
            }
            else -> {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}