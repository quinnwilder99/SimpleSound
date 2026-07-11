package com.simplesound.app.ui.screens.favorites

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.PlaylistGridCard
import com.simplesound.app.ui.components.PlaylistOptionsSheet
import com.simplesound.app.ui.navigation.Routes
import androidx.navigation.NavHostController

/**
 * Favorites tab: a 2-column grid of playlists. "Favorite tracks" is always first;
 * every hearted playlist follows. Press-and-hold a card to shake it and reveal the
 * Play / Add / Share / Remove options.
 */
@Composable
fun FavoritesScreen(vm: AppViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val player = LocalPlayer.current
    val playlists by vm.favoritesTabPlaylists.collectAsStateWithLifecycle()

    var optionsFor by remember { mutableStateOf<Playlist?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        items(playlists, key = { it.id }) { pl ->
            PlaylistGridCard(
                playlist = pl,
                shaking = optionsFor?.id == pl.id,
                onClick = { navController.navigate(Routes.playlist(pl.id)) },
                onLongPress = { optionsFor = pl }
            )
        }
    }

    optionsFor?.let { pl ->
        PlaylistOptionsSheet(
            playlist = pl,
            onPlay = { player.playQueue(vm.tracksByIds(pl.trackIds), 0) },
            onAdd = { player.playQueue(vm.tracksByIds(pl.trackIds), 0) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Check out my playlist \"${pl.name}\" on simpleSOUND")
                }
                context.startActivity(Intent.createChooser(send, "Share playlist"))
            },
            onRemove = {
                // On the Favorites tab, Remove un-hearts a user playlist (it leaves this tab).
                if (pl.kind == PlaylistKind.USER) vm.toggleFavoritePlaylist(pl.id)
            },
            onDismiss = { optionsFor = null }
        )
    }
}
