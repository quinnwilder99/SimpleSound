package com.simplesound.app.ui.screens.tracks

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.AddTracksToPlaylistDialog
import com.simplesound.app.ui.components.DeleteTrackDialog
import com.simplesound.app.ui.components.DeleteTracksDialog
import com.simplesound.app.ui.components.SelectionActionBar
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.TrackActionsSheet
import com.simplesound.app.ui.components.TrackDetailsDialog
import com.simplesound.app.ui.components.TrackRow
import java.io.File

/** All tracks, sortable by date added / name / artist / length. */
@Composable
fun TracksScreen(vm: AppViewModel, onOpenNowPlaying: () -> Unit = {}) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val allTracks by vm.tracks.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()

    val sort by vm.tracksSort.collectAsStateWithLifecycle()
    val sorted = remember(allTracks, sort) { vm.sortedTracks(sort) }

    // ---- Single-track actions ----
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var deleteTrack by remember { mutableStateOf<Track?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }

    // ---- Multi-selection state ----
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showAddMany by remember { mutableStateOf(false) }
    var showDeleteMany by remember { mutableStateOf(false) }

    val selectedTracks: List<Track> = remember(selectedIds, sorted) {
        val byId = sorted.associateBy { it.id }
        selectedIds.mapNotNull { byId[it] }
    }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }

    // Temporarily hide the global persistent mini player while the bottom
    // selection action bar or any modal sheet/dialog is open, so it can't
    // overlay and intercept touches over them. Mirrors SearchScreen.
    val anyOverlayOpen = selectionMode ||
        sheetTrack != null ||
        addTrack != null ||
        deleteTrack != null ||
        detailsTrack != null ||
        showAddMany ||
        showDeleteMany
    LaunchedEffect(anyOverlayOpen) {
        vm.setMiniPlayerHidden(anyOverlayOpen)
    }
    // Always release the flag when leaving the screen so the mini player is
    // restored for the rest of the app.
    DisposableEffect(Unit) {
        onDispose { vm.setMiniPlayerHidden(false) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SortHeader(
                current = sort,
                onSort = { vm.setTracksSort(it) },
                onShuffle = { player.playQueue(sorted.shuffled(), 0, "All tracks") },
                onPlayAll = { player.playQueue(sorted, 0, "All tracks") }
            )
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 160.dp)) {
                items(sorted, key = { it.id }) { track ->
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
                                player.playQueue(sorted, sorted.indexOf(track), "All tracks")
                                onOpenNowPlaying()
                            }
                        },
                        onMore = { sheetTrack = track }
                    )
                }
            }
        }

        // Bottom navigator popup that rises from the bottom of the screen.
        SelectionActionBar(
            selectedCount = selectedIds.size,
            onPlay = {
                if (selectedTracks.isNotEmpty()) {
                    // Temp queue only Ã¢ not persisted as a playlist.
                    player.playQueue(selectedTracks, 0, "Queue")
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

    // ---- Single-track sheet & dialogs ----
    sheetTrack?.let { t ->
        TrackActionsSheet(
            track = t,
            isFavorite = t.id in favoriteIds,
            onPlay = { player.playSingle(t) }, // playSingle already labels as "Queue"
            onToggleFavorite = { vm.toggleFavoriteTrack(t.id) },
            onAddToPlaylist = { addTrack = t; sheetTrack = null },
            onDelete = { deleteTrack = t; sheetTrack = null },
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

    deleteTrack?.let { t ->
        DeleteTrackDialog(
            track = t,
            onConfirm = { vm.deleteTrack(t.id); deleteTrack = null },
            onDismiss = { deleteTrack = null }
        )
    }

    detailsTrack?.let { t ->
        TrackDetailsDialog(track = t, onDismiss = { detailsTrack = null })
    }

    // ---- Multi-track dialogs ----
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