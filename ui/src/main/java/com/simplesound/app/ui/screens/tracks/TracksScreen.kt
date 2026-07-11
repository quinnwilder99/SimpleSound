package com.simplesound.app.ui.screens.tracks

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.simplesound.app.ui.components.DeleteTrackDialog
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

    var sort by remember { mutableStateOf(SortOption.DATE_ADDED) }
    val sorted = remember(allTracks, sort) { vm.sortedTracks(sort) }

    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var deleteTrack by remember { mutableStateOf<Track?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }

    Column(Modifier.fillMaxSize()) {
        SortHeader(
            current = sort,
            onSort = { sort = it },
            onShuffle = { player.playQueue(sorted.shuffled(), 0) },
            onPlayAll = { player.playQueue(sorted, 0) }
        )
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            items(sorted, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onClick = {
                        player.playQueue(sorted, sorted.indexOf(track))
                        onOpenNowPlaying()
                    },
                    onMore = { sheetTrack = track }
                )
            }
        }
    }

    sheetTrack?.let { t ->
        TrackActionsSheet(
            track = t,
            isFavorite = t.id in favoriteIds,
            onPlay = { player.playSingle(t) },
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
