package com.simplesound.app.ui.screens.playlists

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.navigation.Routes
import com.simplesound.app.util.trackCountLabel

/**
 * Playlists tab: the four native playlists (Recently added, Most played, Recently
 * played, Favorite tracks) as large cards up top, then every user playlist below.
 */
@Composable
fun PlaylistsScreen(vm: AppViewModel, navController: NavHostController) {
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    val native = vm.nativePlaylists()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            Text(
                "Custom order",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(native, key = { it.id }) { pl ->
                    NativePlaylistCard(pl) { navController.navigate(Routes.playlist(pl.id)) }
                }
            }
        }
        item { Spacer(Modifier.size(16.dp)) }
        items(userPlaylists, key = { it.id }) { pl ->
            UserPlaylistRow(pl) { navController.navigate(Routes.playlist(pl.id)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp,
                modifier = Modifier.padding(start = 92.dp))
        }
    }
}

@Composable
private fun NativePlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(150.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Artwork(uri = playlist.coverUri, modifier = Modifier.size(150.dp), corner = 16.dp, iconSize = 54.dp)
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            trackCountLabel(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UserPlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(uri = playlist.coverUri, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            trackCountLabel(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
