package com.simplesound.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.model.Tab
import com.simplesound.app.ui.components.GlowBackground
import com.simplesound.app.ui.components.MiniPlayer
import com.simplesound.app.ui.navigation.Routes
import com.simplesound.app.ui.screens.albums.AlbumsScreen
import com.simplesound.app.ui.screens.artists.ArtistsScreen
import com.simplesound.app.ui.screens.favorites.FavoritesScreen
import com.simplesound.app.ui.screens.folders.FoldersScreen
import com.simplesound.app.ui.screens.playlists.PlaylistsScreen
import com.simplesound.app.ui.screens.tracks.TracksScreen

@Composable
fun HomeScreen(vm: AppViewModel, navController: NavHostController) {
    val tabSettings by vm.tabSettings.collectAsStateWithLifecycle()
    val enabledTabs = remember(tabSettings) { tabSettings.filter { it.enabled }.map { it.tab } }

    var selected by rememberSaveable { mutableStateOf(Tab.TRACKS.name) }
    val selectedTab = enabledTabs.firstOrNull { it.name == selected }
        ?: enabledTabs.firstOrNull { it == Tab.TRACKS }
        ?: enabledTabs.firstOrNull()
        ?: Tab.TRACKS

    Box(Modifier.fillMaxSize()) {
        GlowBackground(accent = MaterialTheme.colorScheme.primary)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                MiniPlayer(
                    Modifier.padding(bottom = 6.dp),
                    onClick = { navController.navigate(Routes.NOW_PLAYING) }
                )
            }
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "simpleSOUND", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (selectedTab == Tab.PLAYLISTS) {
                        IconButton(onClick = { vm.createPlaylist("New playlist") }) {
                            Icon(Icons.Rounded.Add, "New playlist", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Search, "Search", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Rounded.MoreVert, "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    enabledTabs.forEach { tab ->
                        val active = tab == selectedTab
                        Text(
                            text = tab.label,
                            style = if (active) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { selected = tab.name }.padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        Tab.FAVORITES -> FavoritesScreen(vm, navController)
                        Tab.TRACKS -> TracksScreen(vm, onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) })
                        Tab.PLAYLISTS -> PlaylistsScreen(vm, navController)
                        Tab.ALBUMS -> AlbumsScreen(vm)
                        Tab.ARTISTS -> ArtistsScreen(vm)
                        Tab.FOLDERS -> FoldersScreen(vm)
                    }
                }
            }
        }
    }
}
