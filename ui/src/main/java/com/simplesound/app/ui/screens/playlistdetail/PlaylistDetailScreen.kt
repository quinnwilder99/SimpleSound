package com.simplesound.app.ui.screens.playlistdetail

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.DEFAULT_PLAYLIST_SORT
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.AddTracksToPlaylistDialog
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.components.CoverCropDialog
import com.simplesound.app.ui.components.PlaylistSelectionActionBar
import com.simplesound.app.ui.components.PlaylistTrackActionsSheet
import com.simplesound.app.ui.components.RemoveTracksDialog
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.TrackDetailsDialog
import com.simplesound.app.ui.components.TrackRow
import com.simplesound.app.ui.components.liquidGlass
import com.simplesound.app.util.CoverImageStore
import com.simplesound.app.util.trackCountLabel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Non-track items rendered above the track list (header card + sort header). */
private const val PLAYLIST_HEADER_ITEMS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    vm: AppViewModel,
    playlistId: String,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit = {},
) {
    val context = LocalContext.current
    val player = LocalPlayer.current

    // Re-read from the flows so name/cover/track edits reflect live. For a native
    // (computed) playlist like "Recently played"/"Most played", playlistById()
    // falls back to re-deriving from the live track list, so tracks/favorites
    // must be watched here too -- otherwise opening e.g. "Recently played" would
    // freeze at whatever snapshot existed on first composition and never pick up
    // tracks played while this screen stays open.
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    val allTracks by vm.tracks.collectAsStateWithLifecycle()
    val favoriteTrackIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val playlist =
        remember(userPlaylists, allTracks, favoriteTrackIds, playlistId) {
            vm.playlistById(playlistId)
        }

    if (playlist == null) {
        onBack()
        return
    }
    val playlistTracks = vm.tracksByIds(playlist.trackIds)
    // Persisted per-playlist sort: the chosen sort stays put across app restarts
    // and is never reset until the user explicitly changes it.
    // vm.playlistSort() launches a new backing collector each time it's called
    // (see its doc comment), so it must be remembered per playlistId rather than
    // invoked directly in the composable body — otherwise every recomposition of
    // this screen (selection changes, dialogs opening, etc.) would leak another
    // eager StateFlow collector into the ViewModel's scope for its whole lifetime.
    //
    // "Most played" / "Recently played" arrive from vm.tracksByIds() already in
    // their meaningful computed order (by play count / last-played time). Unlike
    // "Recently added" (whose computed order already matches DEFAULT_PLAYLIST_SORT,
    // i.e. date-added), that order has no matching SortOption, so the default sort
    // must be CUSTOM_ORDER -- which sortPlaylistTracks() passes through unchanged
    // when no custom order is saved -- or first open would silently re-sort them
    // by date-added and hide the very ordering the playlist exists to show.
    val defaultSort =
        when (playlist.kind) {
            PlaylistKind.MOST_PLAYED, PlaylistKind.RECENTLY_PLAYED -> SortOption.CUSTOM_ORDER
            else -> DEFAULT_PLAYLIST_SORT
        }
    val playlistSortFlow = remember(playlistId) { vm.playlistSort(playlistId, defaultSort) }
    val sort by playlistSortFlow.collectAsStateWithLifecycle()
    // Bumped after each custom-order move so the list recomputes from the newly
    // persisted order. (The custom order lives in the Room-backed store, not a
    // flow observed here, so we need an explicit recomposition trigger.)
    var customOrderVersion by remember { mutableStateOf(0) }
    val sortedTracks =
        remember(playlistTracks, sort, customOrderVersion) {
            vm.sortPlaylistTracks(playlistId, playlistTracks, sort)
        }
    // Local, mutable working copy so a drag-to-reorder updates the list instantly
    // while the gesture is still in flight. Re-seeded whenever the underlying
    // sorted list changes identity (sort switch, tracks added/removed, or the
    // customOrderVersion bump right after a reorder is persisted).
    var tracks by remember(sortedTracks) { mutableStateOf(sortedTracks) }
    val editable = playlist.kind == PlaylistKind.USER

    // ---- Drag-to-reorder (available while selection mode is active) ----
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            val fromIndex = from.index - PLAYLIST_HEADER_ITEMS
            val toIndex = to.index - PLAYLIST_HEADER_ITEMS
            if (fromIndex in tracks.indices && toIndex in tracks.indices) {
                tracks = tracks.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                // A soft tick each time the row crosses a neighbour, so the reorder
                // feels physical rather than silent.
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // ---- Multi-track selection state ----
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showAddMany by remember { mutableStateOf(false) }
    var showRemoveMany by remember { mutableStateOf(false) }

    // ---- Per-row "more" sheet (single track) ----
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addOneTrack by remember { mutableStateOf<Track?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }

    val selectedTracks: List<Track> =
        remember(selectedIds, tracks) {
            val byId = tracks.associateBy { it.id }
            selectedIds.mapNotNull { byId[it] }
        }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    // Hide the global mini player while the selection action bar is on screen
    // so it can't intercept touches on top of the bar. Restore it on exit.
    DisposableEffect(selectionMode) {
        vm.setMiniPlayerHidden(selectionMode)
        onDispose { vm.setMiniPlayerHidden(false) }
    }

    // The photo the user just picked, waiting to be framed in the crop editor.
    // The picker's read grant lasts until the process dies, which is long enough
    // for the editor to decode it once; the framed region is then baked into a
    // file we own, so nothing here needs a persistable URI permission.
    var pendingCover by remember { mutableStateOf<Uri?>(null) }

    val coverPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) pendingCover = uri
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
                                "Favorite",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    renaming = true
                                },
                            )
                            DropdownMenuItem(text = { Text("Change cover") }, onClick = {
                                menuOpen = false
                                coverPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = {
                                menuOpen = false
                                deleting = true
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .liquidGlass(corner = 28.dp, bodyAlpha = 0.09f)
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(
                            uri = playlist.coverUri,
                            modifier = Modifier.size(170.dp),
                            corner = 20.dp,
                            iconSize = 64.dp,
                        )
                        Spacer(Modifier.size(14.dp))
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            trackCountLabel(playlist.trackCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    SortHeader(
                        current = sort,
                        onSort = { vm.setPlaylistSort(playlistId, it) },
                        onShuffle = { if (tracks.isNotEmpty()) player.playQueue(tracks.shuffled(), 0, playlist.name) },
                        onPlayAll = { if (tracks.isNotEmpty()) player.playQueue(tracks, 0, playlist.name) },
                    )
                }
                items(tracks, key = { it.id }) { track ->
                    val selected = track.id in selectedIds
                    val index = tracks.indexOf(track)
                    ReorderableItem(reorderState, key = track.id, enabled = selectionMode) { isDragging ->
                        // The row being dragged lifts with a shadow.
                        val lift by animateDpAsState(
                            targetValue = if (isDragging) 10.dp else 0.dp,
                            label = "reorder-lift",
                        )
                        // Every selected row jiggles for as long as selection mode
                        // is active — the cue that these tracks are "picked up" and
                        // can be dragged. Slightly different periods per row keep
                        // the wiggles from marching in lockstep.
                        val wiggle =
                            if (selected) {
                                val transition = rememberInfiniteTransition(label = "select-wiggle")
                                val angle by transition.animateFloat(
                                    initialValue = -1.1f,
                                    targetValue = 1.1f,
                                    animationSpec =
                                        infiniteRepeatable(
                                            animation =
                                                tween(
                                                    durationMillis = if (track.id % 2 == 0L) 122 else 104,
                                                    easing = LinearEasing,
                                                ),
                                            repeatMode = RepeatMode.Reverse,
                                        ),
                                    label = "select-wiggle-angle",
                                )
                                angle
                            } else {
                                0f
                            }
                        TrackRow(
                            track = track,
                            modifier =
                                Modifier
                                    .shadow(lift, RoundedCornerShape(20.dp))
                                    .graphicsLayer { rotationZ = wiggle },
                            handleModifier =
                                Modifier.draggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.setPlaylistCustomOrder(playlistId, tracks.map { it.id })
                                        // A manual reorder only sticks under CUSTOM_ORDER, so
                                        // switch to it if some other sort was active.
                                        if (sort != SortOption.CUSTOM_ORDER) {
                                            vm.setPlaylistSort(playlistId, SortOption.CUSTOM_ORDER)
                                        }
                                        customOrderVersion++
                                    },
                                ),
                            selectionMode = selectionMode,
                            selected = selected,
                            onLongClick = { toggleSelected(track.id) },
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(track.id)
                                } else {
                                    player.playQueue(tracks, index, playlist.name)
                                    onOpenNowPlaying()
                                }
                            },
                            onMore = { sheetTrack = track },
                        )
                    }
                }
            }

            // Multi-selection action bar: Play / Add / Share / Remove.
            PlaylistSelectionActionBar(
                selectedCount = selectedIds.size,
                modifier = Modifier.align(Alignment.BottomCenter),
                onPlay = {
                    if (selectedTracks.isNotEmpty()) {
                        // Play only the selected tracks as a temporary queue.
                        player.playQueue(selectedTracks, 0, playlist.name)
                        onOpenNowPlaying()
                        clearSelection()
                    }
                },
                onAdd = { showAddMany = true },
                onShare = {
                    // Share intentionally not implemented yet — surface a clear
                    // placeholder so the button isn't silently dead.
                    Toast.makeText(context, "Sharing is not available yet.", Toast.LENGTH_SHORT).show()
                },
                onRemove = { if (editable) showRemoveMany = true },
                onClear = { clearSelection() },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            initial = playlist.name,
            onConfirm = { newName ->
                vm.renamePlaylist(playlistId, newName)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (deleting) {
        DeletePlaylistDialog(
            name = playlist.name,
            onConfirm = {
                CoverImageStore.deleteIfOwned(context, playlist.coverUri)
                vm.deletePlaylist(playlistId)
                deleting = false
                onBack()
            },
            onDismiss = { deleting = false },
        )
    }

    // ---- Multi-track: Add to other playlists ----
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
            onDismiss = { showAddMany = false },
        )
    }

    // ---- Multi-track: Remove from this playlist ----
    if (showRemoveMany && selectedIds.isNotEmpty() && editable) {
        RemoveTracksDialog(
            count = selectedIds.size,
            onConfirm = {
                vm.removeTracksFromPlaylist(playlistId, selectedIds.toList())
                clearSelection()
                showRemoveMany = false
            },
            onDismiss = { showRemoveMany = false },
        )
    }

    // ---- Single-track "more" sheet: Add / Remove / Track details ----
    sheetTrack?.let { t ->
        PlaylistTrackActionsSheet(
            track = t,
            onAddToPlaylist = {
                sheetTrack = null
                addOneTrack = t
            },
            onRemoveFromPlaylist = {
                if (editable) vm.removeTracksFromPlaylist(playlistId, listOf(t.id))
            },
            onDetails = {
                sheetTrack = null
                detailsTrack = t
            },
            onDismiss = { sheetTrack = null },
        )
    }

    // Add this single track to another (existing) playlist.
    addOneTrack?.let { t ->
        AddToPlaylistDialog(
            playlists = userPlaylists.filter { it.id != playlistId },
            onPick = { pl ->
                vm.addTracksToPlaylist(pl.id, listOf(t.id))
                addOneTrack = null
            },
            onDismiss = { addOneTrack = null },
        )
    }

    // Track details dialog.
    detailsTrack?.let { t ->
        TrackDetailsDialog(track = t, onDismiss = { detailsTrack = null })
    }

    // Frame a just-picked photo before it becomes the cover: zoom/pan to choose
    // which part sits in the centre of the (square) cover.
    pendingCover?.let { uri ->
        CoverCropDialog(
            sourceUri = uri,
            playlistId = playlistId,
            previousCoverUri = playlist.coverUri,
            onConfirm = { coverUri ->
                vm.setPlaylistCover(playlistId, coverUri)
                pendingCover = null
            },
            onDismiss = { pendingCover = null },
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { initial }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Confirmation dialog before permanently deleting a playlist. */
@Composable
private fun DeletePlaylistDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Delete playlist") },
        text = { Text("Delete \"$name\" and its tracks from this playlist? This cannot be undone.") },
    )
}
