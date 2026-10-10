package com.simplesound.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplesound.app.data.model.Track

/**
 * Full-screen picker for adding library tracks to one playlist (the "+" on a
 * playlist's screen). Tracks already in the playlist are shown as "Added" and
 * can't be picked again; nothing is written until the "Add" button is pressed.
 *
 * [search] is the library search (blank query = whole library), so results
 * match the Search screen exactly.
 */
@Composable
fun AddTracksPickerDialog(
    playlistName: String,
    alreadyInPlaylist: Set<Long>,
    search: (String) -> List<Track>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Kept as an ordered list so tracks are appended in the order they were tapped.
    var pickedIds by remember { mutableStateOf(emptyList<Long>()) }
    val results =
        remember(query) {
            search(query).sortedBy { it.title.lowercase() }
        }

    fun toggle(id: Long) {
        pickedIds = if (id in pickedIds) pickedIds - id else pickedIds + id
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding(),
        ) {
            PickerHeader(
                playlistName = playlistName,
                pickedCount = pickedIds.size,
                onCancel = onDismiss,
                onAdd = { onAdd(pickedIds) },
            )
            PickerSearchField(query = query, onQueryChange = { query = it })

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (results.isEmpty()) {
                    Text(
                        if (query.isBlank()) "Your library is empty" else "No results for \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(results, key = { it.id }) { track ->
                            PickerRow(
                                track = track,
                                added = track.id in alreadyInPlaylist,
                                picked = track.id in pickedIds,
                                onToggle = { toggle(track.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    track: Track,
    added: Boolean,
    picked: Boolean,
    onToggle: () -> Unit,
) {
    val active = picked && !added
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .liquidGlass(
                corner = 20.dp,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                bodyAlpha = if (active) 0.16f else 0.05f,
                showGloss = active,
                showRim = active,
            )
            .clickable(enabled = !added, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            uri = track.albumArtUri,
            embeddedSource = track.uri,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (added) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistOrUnknown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        PickerIndicator(added = added, picked = picked)
    }
}

@Composable
private fun PickerHeader(
    playlistName: String,
    pickedCount: Int,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Add tracks",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "to $playlistName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAdd, enabled = pickedCount > 0) {
            Text(if (pickedCount == 0) "Add" else "Add $pickedCount")
        }
    }
}

@Composable
private fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search songs, artists, albums") },
        leadingIcon = {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Clear, "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}

/** Trailing marker on a picker row: "Added" if already in the playlist, else a pick circle. */
@Composable
private fun PickerIndicator(
    added: Boolean,
    picked: Boolean,
) {
    if (added) {
        Text(
            "Added",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
    } else {
        Icon(
            if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (picked) "Selected" else "Not selected",
            tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
    }
}
