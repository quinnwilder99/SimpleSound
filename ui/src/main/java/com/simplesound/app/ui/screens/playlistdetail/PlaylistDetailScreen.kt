package com.simplesound.app.ui.screens.playlistdetail

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.TrackRow
import com.simplesound.app.util.trackCountLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    vm: AppViewModel,
    playlistId: String,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit = {}
) {
    val context = LocalContext.current
    val player = LocalPlayer.current

    // Re-read from the flows so name/cover/track edits reflect live.
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    val favIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val playlist = remember(userPlaylists, playlistId) { vm.playlistById(playlistId) }

    if (playlist == null) { onBack(); return }
    val playlistTracks = vm.tracksByIds(playlist.trackIds)
    var sort by remember { mutableStateOf(SortOption.DATE_ADDED) }
    val tracks = remember(playlistTracks, sort) { vm.sortTracks(playlistTracks, sort) }
    val editable = playlist.kind == PlaylistKind.USER

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setPlaylistCover(playlistId, uri.toString())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    if (editable) {
                        IconButton(onClick = { vm.toggleFavoritePlaylist(playlistId) }) {
                            Icon(
                                if (playlist.favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                "Favorite", tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") },
                                onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(text = { Text("Change cover") }, onClick = {
                                menuOpen = false
                                coverPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = {
                                menuOpen = false; vm.deletePlaylist(playlistId); onBack()
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Artwork(uri = playlist.coverUri, modifier = Modifier.size(170.dp), corner = 20.dp, iconSize = 64.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(playlist.name, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(trackCountLabel(playlist.trackCount), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SortHeader(
                    current = sort,
                    onSort = { sort = it },
                    onShuffle = { if (tracks.isNotEmpty()) player.playQueue(tracks.shuffled(), 0, playlist.name) },
                    onPlayAll = { if (tracks.isNotEmpty()) player.playQueue(tracks, 0, playlist.name) }
                )
            }
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onClick = {
                        player.playQueue(tracks, tracks.indexOf(track), playlist.name)
                        onOpenNowPlaying()
                    },
                    onMore = {
                        if (editable) vm.let { /* remove from playlist */ it.let { } }
                    }
                )
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initial = playlist.name,
            onConfirm = { newName -> vm.renamePlaylist(playlistId, newName); renaming = false },
            onDismiss = { renaming = false }
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { initial }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}