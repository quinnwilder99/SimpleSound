package com.simplesound.app.ui.screens.playlists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.PLAYLISTS_TAB_SORTS
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.liquidGlass
import com.simplesound.app.ui.navigation.Routes
import com.simplesound.app.util.trackCountLabel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Playlists tab: the four native playlists (Recently added, Most played, Recently
 * played, Favorite tracks) as large cards up top, then every user playlist below,
 * sorted by Name / Date added / Custom order. In Custom order the rows can be held
 * and dragged; that order (like the other two sorts) is also the order the
 * Favorites tab shows hearted playlists in.
 */
@Composable
fun PlaylistsScreen(
    vm: AppViewModel,
    navController: NavHostController,
) {
    val userPlaylists by vm.sortedUserPlaylists.collectAsStateWithLifecycle()
    val sort by vm.playlistsTabSort.collectAsStateWithLifecycle()
    val reorderable = sort == SortOption.CUSTOM_ORDER
    // nativePlaylists() is a plain (non-flow) snapshot computed from the live
    // track list, so it must be re-derived whenever tracks or favorites change
    // (e.g. a track finishes playing and gets recorded into "Recently played",
    // or the initial MediaStore scan replaces the sample library) -- otherwise
    // this card row would freeze at whatever it first computed and never
    // reflect playback happening while the tab stays open.
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val favoriteTrackIds by vm.favoriteTrackIds.collectAsStateWithLifecycle()
    val native = remember(tracks, favoriteTrackIds) { vm.nativePlaylists() }

    // Local working copy so a drag moves rows instantly; re-seeded whenever the
    // repository publishes (including right after a reorder is persisted).
    var working by remember(userPlaylists) { mutableStateOf(userPlaylists) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            // Only playlist rows have playlist-id keys, so the cards and the sort
            // row above never take part in a move.
            val fromIndex = working.indexOfFirst { it.id == from.key }
            val toIndex = working.indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                working = working.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
    ) {
        item(key = "native") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(native, key = { it.id }) { pl ->
                    NativePlaylistCard(pl) { navController.navigate(Routes.playlist(pl.id)) }
                }
            }
        }
        item(key = "sort") {
            PlaylistsSortRow(
                sort = sort,
                onSort = vm::setPlaylistsTabSort,
                showDragHint = reorderable && working.size > 1,
            )
        }
        items(working, key = { it.id }) { pl ->
            ReorderableItem(reorderState, key = pl.id, enabled = reorderable) { isDragging ->
                val lift by animateFloatAsState(if (isDragging) 1.04f else 1f, label = "reorder-lift")
                UserPlaylistRow(
                    playlist = pl,
                    onClick = { navController.navigate(Routes.playlist(pl.id)) },
                    modifier =
                        Modifier.scale(lift).longPressDraggableHandle(
                            enabled = reorderable,
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = { vm.reorderPlaylists(working.map { it.id }) },
                        ),
                )
            }
        }
    }
}

@Composable
private fun PlaylistsSortRow(
    sort: SortOption,
    onSort: (SortOption) -> Unit,
    showDragHint: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortHeader(
            current = sort,
            onSort = onSort,
            onShuffle = null,
            onPlayAll = null,
            options = PLAYLISTS_TAB_SORTS,
            modifier = Modifier.weight(1f),
        )
        if (showDragHint) {
            Text(
                "Hold and drag to reorder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NativePlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(150.dp)
                // Small fixed row (the four native playlists) — keep a rim for
                // definition but skip the gloss so four cards side by side don't
                // each throw their own highlight.
                .liquidGlass(corner = 22.dp, bodyAlpha = 0.07f, showGloss = false)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(uri = playlist.coverUri, modifier = Modifier.size(146.dp), corner = 16.dp, iconSize = 54.dp)
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            trackCountLabel(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserPlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp)
                // One of these per user playlist, stacked in a LazyColumn — a full
                // gloss+rim per row would read as a stack of glass tiles rather
                // than a list. Keep it to a quiet flat wash instead.
                .liquidGlass(corner = 20.dp, bodyAlpha = 0.05f, showGloss = false, showRim = false)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(uri = playlist.coverUri, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            trackCountLabel(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
