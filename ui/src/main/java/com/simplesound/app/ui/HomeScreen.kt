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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    vm: AppViewModel,
    navController: NavHostController,
) {
    val tabSettings by vm.tabSettings.collectAsStateWithLifecycle()
    val enabledTabs = remember(tabSettings) { tabSettings.filter { it.enabled }.map { it.tab } }

    // Pending creation of a new playlist from the "+" icon in the header.
    var showCreatePlaylist by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { enabledTabs.size })
    val scope = rememberCoroutineScope()

    val firstTabIndex =
        remember(enabledTabs) {
            enabledTabs.indexOfFirst { it == Tab.TRACKS }.takeIf { it >= 0 } ?: 0
        }

    val selectedTab =
        enabledTabs.getOrNull(pagerState.currentPage)
            ?: enabledTabs.getOrNull(firstTabIndex)
            ?: Tab.TRACKS

    // --- Center the active tab label within the horizontally scrollable tab row ---
    // The tab row scrolls independently of the pager. Rather than waiting for the pager
    // to *settle* on a page and then animating the row afterward (which reads as an
    // input-lag "catch up" step), we track the pager's continuous drag position every
    // frame and keep the row's scroll offset locked to it in real time — the same feel
    // as Samsung Sound's tab header, which moves in lockstep with your finger instead of
    // snapping once you let go.
    //
    // Key idea: compute the label's CENTER from measured tab *widths* plus the row's
    // fixed padding/spacing. This is pure arithmetic on layout-stable values — widths
    // don't change just because the row scrolls — so the computed target is inherently
    // scroll-independent, and there's no fighting between our scroll writes and the
    // measurement reads (which the previous positionInRoot-based logic suffered from).
    // The active label changes width when emphasized, which legitimately updates its
    // measured width and re-centers to the exact final position.
    val tabScrollState = rememberScrollState()
    val rowViewportPx = remember { mutableIntStateOf(0) } // visible viewport width (px)
    // index -> measured width (px) of each tab label. Scroll-independent.
    val tabWidths = remember(enabledTabs) { mutableStateMapOf<Int, Int>() }

    // Density-aware conversions for the fixed row padding (16.dp) and tab spacing (20.dp),
    // computed once from the current density.
    val hPaddingPx = with(LocalDensity.current) { 16.dp.roundToPx() } // per-side horizontal padding
    val spacingPx = with(LocalDensity.current) { 20.dp.roundToPx() } // gap between tabs

    // Continuous page position (e.g. 1.35 while 35% swiped from page 1 towards page 2).
    // Reading currentPage/currentPageOffsetFraction directly in composition means this
    // recomposes every frame the pager moves — whether from a finger drag or from the
    // animateScrollToPage() a tab tap triggers below — so both interaction paths get the
    // exact same smooth, continuous follow.
    val pageProgress = pagerState.currentPage + pagerState.currentPageOffsetFraction

    LaunchedEffect(enabledTabs) {
        if (pagerState.currentPage !in enabledTabs.indices && enabledTabs.isNotEmpty()) {
            pagerState.scrollToPage(firstTabIndex)
        }
    }

    // Keep the tab row's scroll offset following the pager every frame, blending between
    // the current page's label center and whichever neighbor it's being dragged towards,
    // proportional to how far the drag has progressed — instead of jumping only once the
    // pager settles on the new page.
    LaunchedEffect(enabledTabs, hPaddingPx, spacingPx) {
        snapshotFlow {
            val page = pagerState.currentPage
            val offsetFraction = pagerState.currentPageOffsetFraction
            // Read all tab widths into an ordered list so recomposition-tracking covers them.
            val widths = enabledTabs.indices.mapNotNull { tabWidths[it] }
            Triple(page, offsetFraction, widths) to rowViewportPx.intValue
        }.collect { (state, viewport) ->
            val (page, offsetFraction, widths) = state
            if (viewport <= 0 || widths.size != enabledTabs.size) return@collect
            if (page !in widths.indices) return@collect

            // Content-space center of tab[index]: padding, then each prior tab width + spacing.
            fun centerOf(index: Int): Int {
                var left = hPaddingPx
                for (i in 0 until index) left += widths[i] + spacingPx
                return left + widths[index] / 2
            }

            val neighbor = page + if (offsetFraction >= 0f) 1 else -1
            val currentCenter = centerOf(page)
            val blendedCenter =
                if (neighbor in widths.indices) {
                    val neighborCenter = centerOf(neighbor)
                    currentCenter + ((neighborCenter - currentCenter) * kotlin.math.abs(offsetFraction)).toInt()
                } else {
                    currentCenter
                }
            val target = (blendedCenter - viewport / 2).coerceIn(0, tabScrollState.maxValue)
            // A direct (non-animated) scrollTo, called every frame the pager moves, IS the
            // animation — it rides the pager's own motion rather than racing a separate
            // animateScrollTo() against it.
            tabScrollState.scrollTo(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlowBackground(accent = MaterialTheme.colorScheme.primary)
        Scaffold(
            containerColor = Color.Transparent,
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Simple Sound",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                    )
                    Spacer(Modifier.weight(1f))
                    if (selectedTab == Tab.PLAYLISTS) {
                        IconButton(onClick = { showCreatePlaylist = true }) {
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                // The viewport width (the on-screen width of the scrollable row) is
                                // what we center the selected label within. This MUST be measured
                                // here, before .horizontalScroll(), because inside a scrollable
                                // container the child is measured with unbounded width — placing
                                // onGloballyPositioned after horizontalScroll (as before) captured
                                // the row's unconstrained *content* width (sum of all tab widths),
                                // not the actual visible screen width, which threw off the centering
                                // math below.
                                rowViewportPx.intValue = coords.size.width
                            }
                            .horizontalScroll(tabScrollState)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    enabledTabs.forEachIndexed { index, tab ->
                        // How "selected" this tab looks right now: 1 at pageProgress == index,
                        // fading linearly to 0 a full page away. Driven by the same continuous
                        // pageProgress as the row scroll above, so the label's size/weight/color
                        // glide in step with the swipe instead of snapping the instant the
                        // pager's current-page flips.
                        val emphasis = (1f - kotlin.math.abs(pageProgress - index)).coerceIn(0f, 1f)
                        Text(
                            text = tab.label,
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontSize =
                                        androidx.compose.ui.unit.lerp(
                                            MaterialTheme.typography.titleLarge.fontSize,
                                            MaterialTheme.typography.headlineLarge.fontSize,
                                            emphasis,
                                        ),
                                ),
                            color =
                                androidx.compose.ui.graphics.lerp(
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    MaterialTheme.colorScheme.primary,
                                    emphasis,
                                ),
                            fontWeight =
                                androidx.compose.ui.text.font.lerp(
                                    FontWeight.Normal,
                                    FontWeight.Bold,
                                    emphasis,
                                ),
                            modifier =
                                Modifier
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
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val tab = enabledTabs.getOrNull(page) ?: Tab.TRACKS
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            Tab.FAVORITES -> FavoritesScreen(vm, navController)
                            Tab.TRACKS ->
                                TracksScreen(
                                    vm,
                                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                                )
                            Tab.PLAYLISTS -> PlaylistsScreen(vm, navController)
                            Tab.ALBUMS -> AlbumsScreen(vm)
                            Tab.ARTISTS -> ArtistsScreen(vm)
                            Tab.FOLDERS -> FoldersScreen(vm)
                        }
                    }
                }
            }
        }

        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    vm.createPlaylist(name)
                    showCreatePlaylist = false
                },
                onDismiss = { showCreatePlaylist = false },
            )
        }
    }
}

/**
 * Asks the user for a name before creating a new playlist from the "+" icon in
 * the header. Empty names fall back to "New playlist", matching the repository's
 * default behavior.
 */
@Composable
private fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { "New playlist" }) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
