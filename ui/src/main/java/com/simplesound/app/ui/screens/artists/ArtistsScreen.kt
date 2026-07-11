package com.simplesound.app.ui.screens.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.CircleGlyph
import com.simplesound.app.util.trackCountLabel

/** Artists tab: tracks grouped by artist. Tap plays everything by that artist. */
@Composable
fun ArtistsScreen(vm: AppViewModel) {
    val player = LocalPlayer.current
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val artists = remember(tracks) {
        tracks.groupBy { it.artistOrUnknown }.toList().sortedBy { it.first.lowercase() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(artists, key = { it.first }) { (artist, list) ->
            Row(
                Modifier.fillMaxWidth().clickable { player.playQueue(list, 0) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                CircleGlyph(Icons.Rounded.Person, Modifier.size(52.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    artist, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(
                    trackCountLabel(list.size), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp,
                modifier = Modifier.padding(start = 84.dp))
        }
    }
}
