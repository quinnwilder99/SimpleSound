package com.simplesound.app.ui.screens.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.util.trackCountLabel

private data class AlbumGroup(val name: String, val artist: String, val artUri: String?, val tracks: List<Track>)

/** Albums tab: tracks grouped by album into a 2-column grid. Tap plays the album. */
@Composable
fun AlbumsScreen(vm: AppViewModel) {
    val player = LocalPlayer.current
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val albums = remember(tracks) {
        tracks.groupBy { it.albumOrUnknown }
            .map { (name, list) ->
                AlbumGroup(
                    name = name,
                    artist = list.first().artistOrUnknown,
                    artUri = list.firstOrNull { it.albumArtUri != null }?.albumArtUri,
                    tracks = list
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp)
    ) {
        items(albums, key = { it.name }) { album ->
            Column(Modifier.padding(8.dp).clickable { player.playQueue(album.tracks, 0) }) {
                Artwork(
                    uri = album.artUri,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    corner = 16.dp,
                    iconSize = 48.dp
                )
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "${album.artist} · ${trackCountLabel(album.tracks.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
