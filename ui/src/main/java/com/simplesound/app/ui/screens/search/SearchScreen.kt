package com.simplesound.app.ui.screens.search

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.AddTracksToPlaylistDialog
import com.simplesound.app.ui.components.DeleteTracksDialog
import com.simplesound.app.ui.components.SelectionActionBar
import com.simplesound.app.ui.components.TrackActionsSheet
import com.simplesound.app.ui.components.TrackDetailsDialog
import com.simplesound.app.ui.components.TrackRow
import java.io.File

/**
 * Search / look-up screen. Lets the user find any track in the library by title,
 * artist, or album. Results update live as the query changes, and tapping a row
 * plays the full result list starting from that track. Long-press a row to enter
 * multi-selection mode (mirrors TracksScreen): play, add to playlist, delete, or
 * clear the selection via the bottom action bar.
 */
@Composable
fun SearchScreen(vm: AppViewModel, onBack: () -> Unit, onOpenNowPlaying: () -> Unit = {}) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val favoriteIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    val results = remember(query) { vm.searchTracks(query) }

    // ---- Single-track actions (mirrors TracksScreen) ----
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }

    // ---- Multi-selection state (mirrors TracksScreen) ----
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showAddMany by remember { mutableStateOf(false) }
    var showDeleteMany by remember { mutableStateOf(false) }

    val selectedTracks: List<Track> = remember(selectedIds, results) {
        val byId = results.associateBy { it.id }
        selectedIds.mapNotNull { byId[it] }
    }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Search songs, artists, albums") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                )
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (results.isEmpty()) {
                Text(
                    text = if (query.isBlank()) "Type to search your library" else "No results for \"$query\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                    items(results, key = { it.id }) { track ->
                        val selected = track.id in selectedIds
                        TrackRow(
                            track = track,
                            selectionMode = selectionMode,
                            selected = selected,
                            onLongClick = { toggleSelected(track.id) },
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(track.id)
                                } else {
                                    player.playQueue(results, results.indexOf(track), "Search results")
                                    onOpenNowPlaying()
                                }
                            },
                            onMore = { sheetTrack = track }
                        )
                    }
                }
            }

            // Bottom action bar for multi-selection (mirrors TracksScreen).
            SelectionActionBar(
                selectedCount = selectedIds.size,
                onPlay = {
                    if (selectedTracks.isNotEmpty()) {
                        player.playQueue(selectedTracks, 0, "Search results")
                        onOpenNowPlaying()
                        clearSelection()
                    }
                },
                onAdd = { showAddMany = true },
                onDelete = { showDeleteMany = true },
                onClear = { clearSelection() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // ---- Single-track sheet & dialogs ----
    sheetTrack?.let { t ->
        TrackActionsSheet(
            track = t,
            isFavorite = t.id in favoriteIds,
            onPlay = { player.playSingle(t) },
            onToggleFavorite = { vm.toggleFavoriteTrack(t.id) },
            onAddToPlaylist = { addTrack = t; sheetTrack = null },
            onDelete = { /* delete not offered from search */ },
            onShare = { shareTrack(context, t) },
            onDetails = { detailsTrack = t; sheetTrack = null },
            onDismiss = { sheetTrack = null }
        )
    }

    addTrack?.let { t ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onPick = { pl -> vm.addTracksToPlaylist(pl.id, listOf(t.id)); addTrack = null },
            onDismiss = { addTrack = null }
        )
    }

    detailsTrack?.let { t ->
        TrackDetailsDialog(track = t, onDismiss = { detailsTrack = null })
    }

    // ---- Multi-track dialogs (mirrors TracksScreen) ----
    if (showAddMany && selectedIds.isNotEmpty()) {
        AddTracksToPlaylistDialog(
            playlists = userPlaylists,
            pickedCount = selectedIds.size,
            onAddToExisting = { pl ->
                vm.addTracksToPlaylist(pl.id, selectedIds.toList())
                clearSelection()
                showAddMany = false
            },
            onCreateNew = { name ->
                vm.createPlaylist(name, selectedIds.toList())
                clearSelection()
                showAddMany = false
            },
            onDismiss = { showAddMany = false }
        )
    }

    if (showDeleteMany && selectedIds.isNotEmpty()) {
        DeleteTracksDialog(
            count = selectedIds.size,
            onConfirm = {
                vm.deleteTracks(selectedIds.toList())
                clearSelection()
                showDeleteMany = false
            },
            onDismiss = { showDeleteMany = false }
        )
    }
}

/** Share a track's file via Android's share sheet. */
private fun shareTrack(context: android.content.Context, track: Track) {
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