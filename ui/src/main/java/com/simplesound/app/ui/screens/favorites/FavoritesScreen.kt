package com.simplesound.app.ui.screens.favorites

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.PlaylistGridCard
import com.simplesound.app.ui.components.PlaylistOptionsSheet
import com.simplesound.app.ui.navigation.Routes
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * Favorites tab: a 2-column grid of playlists. "Favorite tracks" is always first;
 * every hearted playlist follows (newest heart on top until the user picks an
 * order). Press-and-hold a card to shake it and reveal the Play / Add / Share /
 * Remove / Change order options. Change order puts the grid in a reorder mode:
 * the hearted cards wiggle and can be held and dragged; Done (or Back) exits.
 */
@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    navController: NavHostController,
) {
    val playlists by vm.favoritesTabPlaylists.collectAsStateWithLifecycle()

    var optionsFor by remember { mutableStateOf<Playlist?>(null) }
    var reordering by remember { mutableStateOf(false) }

    // Local working copy so a drag moves cards instantly; re-seeded whenever the
    // repository publishes (including right after a reorder is persisted).
    var working by remember(playlists) { mutableStateOf(playlists) }

    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val reorderState =
        rememberReorderableLazyGridState(gridState) { from, to ->
            moveCard(working, from.key, to.key)?.let {
                working = it
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

    BackHandler(enabled = reordering) { reordering = false }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        // Leave room at the bottom so the last row of cards clears the global
        // mini player, which floats over every screen's bottom edge. Matches the
        // 96.dp allowance the Playlists tab uses.
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp),
    ) {
        if (reordering) {
            item(key = "reorder-header", span = { GridItemSpan(maxLineSpan) }) {
                ReorderHeader(onDone = { reordering = false })
            }
        }
        items(working, key = { it.id }) { pl ->
            val movable = reordering && pl.kind == PlaylistKind.USER
            ReorderableItem(reorderState, key = pl.id, enabled = movable) { isDragging ->
                val lift by animateFloatAsState(if (isDragging) 1.06f else 1f, label = "reorder-lift")
                PlaylistGridCard(
                    playlist = pl,
                    shaking = movable || optionsFor?.id == pl.id,
                    interactive = !reordering,
                    onClick = { navController.navigate(Routes.playlist(pl.id)) },
                    onLongPress = { optionsFor = pl },
                    modifier =
                        Modifier.scale(lift).longPressDraggableHandle(
                            enabled = movable,
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = {
                                vm.reorderFavoritePlaylists(
                                    working.filter { it.kind == PlaylistKind.USER }.map { it.id },
                                )
                            },
                        ),
                )
            }
        }
    }

    optionsFor?.let { pl ->
        FavoriteOptionsSheet(
            playlist = pl,
            vm = vm,
            onChangeOrder = { reordering = true },
            onDismiss = { optionsFor = null },
        )
    }
}

/**
 * [cards] with the card keyed [fromKey] moved to [toKey]'s slot, or null if the move
 * isn't allowed: only hearted user playlists move, so "Favorite tracks" stays pinned
 * first (and the reorder-mode header isn't a card, so it never matches).
 */
private fun moveCard(
    cards: List<Playlist>,
    fromKey: Any,
    toKey: Any,
): List<Playlist>? {
    val fromIndex = cards.indexOfFirst { it.id == fromKey }
    val toIndex = cards.indexOfFirst { it.id == toKey }
    if (fromIndex < 0 || toIndex < 0 || cards[toIndex].kind != PlaylistKind.USER) return null
    return cards.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

@Composable
private fun ReorderHeader(onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Hold and drag to reorder",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun FavoriteOptionsSheet(
    playlist: Playlist,
    vm: AppViewModel,
    onChangeOrder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player = LocalPlayer.current
    PlaylistOptionsSheet(
        playlist = playlist,
        onPlay = { player.playQueue(vm.tracksByIds(playlist.trackIds), 0) },
        onAdd = { player.playQueue(vm.tracksByIds(playlist.trackIds), 0) },
        onShare = {
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Check out my playlist \"${playlist.name}\" on simpleSOUND")
                }
            context.startActivity(Intent.createChooser(send, "Share playlist"))
        },
        onRemove = {
            // On the Favorites tab, Remove un-hearts a user playlist (it leaves this tab).
            if (playlist.kind == PlaylistKind.USER) vm.toggleFavoritePlaylist(playlist.id)
        },
        onChangeOrder = onChangeOrder,
        onDismiss = onDismiss,
    )
}
