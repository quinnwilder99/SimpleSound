package com.simplesound.app.ui.screens.tracks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.AddTracksToPlaylistDialog
import com.simplesound.app.ui.components.AlphabetScrollbar
import com.simplesound.app.ui.components.AlphabetScrollbarRowEndInset
import com.simplesound.app.ui.components.SelectionActionBar
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.TrackActionsSheet
import com.simplesound.app.ui.components.TrackDetailsDialog
import com.simplesound.app.ui.components.TrackRow
import com.simplesound.app.ui.components.TrackTagEditor
import com.simplesound.app.ui.components.rememberTrackDeleter
import com.simplesound.app.ui.components.rememberTrackTagEditor
import com.simplesound.app.util.shareTrack

/** Sort options offered for the flat Tracks list, i.e. everything except
 *  Custom order (which needs a specific playlist to hang its saved order off
 *  of — see [SortHeader]'s `options` doc comment). */
private val TRACKS_TAB_SORT_OPTIONS = SortOption.entries.filterNot { it == SortOption.CUSTOM_ORDER }

/** All tracks, sortable by date added / name / artist / length. */
@Composable
fun TracksScreen(
    vm: AppViewModel,
    onOpenNowPlaying: () -> Unit = {},
) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val allTracks by vm.tracks.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()

    val sort by vm.tracksSort.collectAsStateWithLifecycle()
    val sorted = remember(allTracks, sort) { vm.sortedTracks(sort) }
    val listState = rememberLazyListState()

    // ---- Single-track actions ----
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var detailsTrack by remember { mutableStateOf<Track?>(null) }

    // ---- Multi-selection state ----
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()

    // The A–Z bar shows only while sorted by name and outside selection mode;
    // rows then pull their cards in from the right so the bar never sits on them.
    val showAlphabetBar = sort == SortOption.NAME && !selectionMode && sorted.isNotEmpty()
    val rowEndInset by animateDpAsState(
        targetValue = if (showAlphabetBar) AlphabetScrollbarRowEndInset else 0.dp,
        label = "alphabet-row-inset",
    )
    var showAddMany by remember { mutableStateOf(false) }

    val selectedTracks: List<Track> =
        remember(selectedIds, sorted) {
            val byId = sorted.associateBy { it.id }
            selectedIds.mapNotNull { byId[it] }
        }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    val deleter = rememberTrackDeleter(vm, onDeleted = { deleted -> selectedIds = selectedIds - deleted })
    val tagEditor = rememberTrackTagEditor(vm)

    // Temporarily hide the global persistent mini player while the bottom
    // selection action bar or any modal sheet/dialog is open, so it can't
    // overlay and intercept touches over them. Mirrors SearchScreen.
    val anyOverlayOpen =
        selectionMode ||
            sheetTrack != null ||
            addTrack != null ||
            deleter.isConfirming ||
            tagEditor.isOpen ||
            detailsTrack != null ||
            showAddMany
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
                onPlayAll = { player.playQueue(sorted, 0, "All tracks") },
                // No playlist context here, so Custom order (which needs one)
                // isn't offered — see SortHeader's `options` doc comment.
                options = TRACKS_TAB_SORT_OPTIONS,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 160.dp),
                ) {
                    items(sorted, key = { it.id }) { track ->
                        val selected = track.id in selectedIds
                        TrackRow(
                            track = track,
                            modifier = Modifier.padding(end = rowEndInset),
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
                            onMore = { sheetTrack = track },
                        )
                    }
                }

                // A–Z fast-scroll bar, only while sorted by name. Hidden in selection
                // mode so it never competes with the selection action bar.
                if (showAlphabetBar) {
                    AlphabetScrollbar(
                        tracks = sorted,
                        listState = listState,
                        // Bottom inset matches the list's padding so the bar clears the mini player.
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 160.dp, end = 2.dp),
                    )
                }
            }
        }

        // Bottom navigator popup that rises from the bottom of the screen.
        SelectionActionBar(
            selectedCount = selectedIds.size,
            onPlay = {
                if (selectedTracks.isNotEmpty()) {
                    // Temp queue only — not persisted as a playlist.
                    player.playQueue(selectedTracks, 0, "Queue")
                    onOpenNowPlaying()
                    clearSelection()
                }
            },
            onAdd = { showAddMany = true },
            onDelete = { deleter.request(selectedTracks) },
            onClear = { clearSelection() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ---- Single-track sheet & dialogs ----
    sheetTrack?.let { t ->
        TrackActionsSheet(
            track = t,
            isFavorite = t.id in favoriteIds,
            // playSingle already labels as "Queue"
            onPlay = { player.playSingle(t) },
            onToggleFavorite = { vm.toggleFavoriteTrack(t.id) },
            onAddToPlaylist = {
                addTrack = t
                sheetTrack = null
            },
            onDelete = {
                sheetTrack = null
                deleter.request(listOf(t))
            },
            onShare = { shareTrack(context, t) },
            onDetails = {
                detailsTrack = t
                sheetTrack = null
            },
            onDismiss = { sheetTrack = null },
            onEditTags =
                if (TrackTagEditor.canEdit(t)) {
                    {
                        sheetTrack = null
                        tagEditor.open(t)
                    }
                } else {
                    null
                },
        )
    }

    addTrack?.let { t ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onPick = { picked ->
                picked.forEach { pl -> vm.addTracksToPlaylist(pl.id, listOf(t.id)) }
                addTrack = null
            },
            onDismiss = { addTrack = null },
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
            onAddToExisting = { picked ->
                picked.forEach { pl -> vm.addTracksToPlaylist(pl.id, selectedIds.toList()) }
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
}
