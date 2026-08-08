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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.model.Tab
import com.simplesound.app.ui.components.GlowBackground
import com.simplesound.app.ui.navigation.Routes
import com.simplesound.app.ui.screens.albums.AlbumsScreen
import com.simplesound.app.ui.screens.artists.ArtistsScreen
import com.simplesound.app.ui.screens.favorites.FavoritesScreen
import com.simplesound.app.ui.screens.folders.FoldersScreen
import com.simplesound.app.ui.screens.playlists.PlaylistsScreen
import com.simplesound.app.ui.screens.tracks.TracksScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AppViewModel, navController: NavHostController) {
    val tabSettings by vm.tabSettings.collectAsStateWithLifecycle()
    val enabledTabs = remember(tabSettings) { tabSettings.filter { it.enabled }.map { it.tab } }

    val pagerState = rememberPagerState(pageCount = { enabledTabs.size })
    val scope = rememberCoroutineScope()

    val firstTabIndex = remember(enabledTabs) {
        enabledTabs.indexOfFirst { it == Tab.TRACKS }.takeIf { it >= 0 } ?: 0
    }

    val selectedTab = enabledTabs.getOrNull(pagerState.currentPage)
        ?: enabledTabs.getOrNull(firstTabIndex)
        ?: Tab.TRACKS

    // --- Center the active tab label within the horizontally scrollable tab row ---
    // The tab row scrolls independently of the pager. Whenever the pager settles on a
    // new page (from a swipe OR a tap), we animate the row so the selected label ends up
    // visually centered in the viewport.
    //
    // Key idea: compute the label's CENTER from measured tab *widths* plus the row's
    // fixed padding/spacing. This is pure arithmetic on layout-stable values — widths
    // don't change just because the row scrolls — so the computed target is inherently
    // scroll-independent. Our own animateScrollTo() therefore never re-triggers this
    // computation, so there are no fighting animations and no jitter (which the previous
    // positionInRoot-based logic suffered from whenever the relayout callbacks raced the
    // scroll). The active label changes width when emphasized, which legitimately updates
    // its measured width and re-centers to the exact final position.
    val tabScrollState = rememberScrollState()
    val rowViewportPx = remember { mutableIntStateOf(0) }     // visible viewport width (px)
    // index -> measured width (px) of each tab label. Scroll-independent.
    val tabWidths = remember(enabledTabs) { mutableStateMapOf<Int, Int>() }
    // A single coroutine for centering so overlapping animateScrollTo() calls can't fight.
    var centeringJob by remember { mutableStateOf<Job?>(null) }

    // Density-aware conversions for the fixed row padding (16.dp) and tab spacing (20.dp),
    // computed once from the current density.
    val hPaddingPx = with(LocalDensity.current) { 16.dp.roundToPx() }   // per-side horizontal padding
    val spacingPx = with(LocalDensity.current) { 20.dp.roundToPx() }    // gap between tabs

    LaunchedEffect(enabledTabs) {
        if (pagerState.currentPage !in enabledTabs.indices && enabledTabs.isNotEmpty()) {
            pagerState.scrollToPage(firstTabIndex)
        }
    }

    // Re-center whenever the pager settles on a new page (swipe, tap, or the initial
    // restore above), or when the settled tab's measured width / the viewport changes
    // (e.g. the active label finishing its emphasis resize). The snapshot reads the
    // settled page's width and the widths needed to sum up to it, so a stale/intermediate
    // target is always corrected to the final one.
    LaunchedEffect(enabledTabs, hPaddingPx, spacingPx) {
        snapshotFlow {
            val page = pagerState.settledPage
            // Read all tab widths into an ordered list so recomposition-tracking covers them.
            val widths = enabledTabs.indices.mapNotNull { tabWidths[it] }
            Triple(page, widths, rowViewportPx.intValue)
        }.collect { (page, widths, viewport) ->
            if (viewport <= 0 || widths.size != enabledTabs.size) return@collect
            if (page !in widths.indices) return@collect
            // Content-space left of tab[page]: padding, then each prior tab width + spacing.
            var left = hPaddingPx
            for (i in 0 until page) left += widths[i] + spacingPx
            val center = left + widths[page] / 2
            val target = (center - viewport / 2).coerceIn(0, tabScrollState.maxValue)
            centeringJob?.cancel()
            centeringJob = scope.launch { tabScrollState.animateScrollTo(target) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlowBackground(accent = MaterialTheme.colorScheme.primary)
        Scaffold(
            containerColor = Color.Transparent
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Simple Sound",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(Modifier.weight(1f))
                    if (selectedTab == Tab.PLAYLISTS) {
                        IconButton(onClick = { vm.createPlaylist("New playlist") }) {
                            Icon(Icons.Rounded.Add, "New playlist", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { navController.navigate(Routes.SEARCH) }) {
                        Icon(Icons.Rounded.Search, "Search", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Rounded.MoreVert, "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(tabScrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onGloballyPositioned { coords ->
                            // The viewport width (the on-screen width of the scrollable row) is
                            // what we center the selected label within.
                            rowViewportPx.intValue = coords.size.width
                        },
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    enabledTabs.forEachIndexed { index, tab ->
                        val active = tab == selectedTab
                        Text(
                            text = tab.label,
                            style = if (active) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    // Record this tab's FULL outer footprint (incl. its inner
                                    // padding) so the content-offset sum matches how the row
                                    // actually lays tabs out. Width is scroll-independent, so
                                    // animating the row never re-triggers the centering math.
                                    val w = coords.size.width
                                    if (w > 0 && tabWidths[index] != w) tabWidths[index] = w
                                }
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val tab = enabledTabs.getOrNull(page) ?: Tab.TRACKS
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
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
}
