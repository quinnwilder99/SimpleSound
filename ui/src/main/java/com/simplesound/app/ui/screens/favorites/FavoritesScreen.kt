package com.simplesound.app.ui.screens.favorites

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.simplesound.app.data.DEFAULT_PLAYLIST_SORT
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.components.AddToPlaylistDialog
import com.simplesound.app.ui.components.Artwork
import com.simplesound.app.ui.components.PlaylistOptionsSheet
import com.simplesound.app.ui.components.PlaylistTrackActionsSheet
import com.simplesound.app.ui.components.SortHeader
import com.simplesound.app.ui.components.TrackDetailsDialog
import com.simplesound.app.ui.components.TrackRow
import com.simplesound.app.ui.components.TrackTagEditor
import com.simplesound.app.ui.components.rememberTrackTagEditor
import com.simplesound.app.ui.navigation.Routes
import com.simplesound.app.util.trackCountLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** How far (each way) the track list drifts sideways while a playlist swap fades it. */
private val SwapDrift = 48.dp

/** Room under the covers for the playlist name and track count. */
private val TitleBlock = 72.dp

/**
 * Favorites tab: a carousel of round playlist covers on top ("Favorite tracks"
 * first, then every hearted playlist) with the selected playlist's tracks below.
 *
 * Swipe the carousel, or tap the cover peeking in on either side, to switch
 * playlist. The carousel is just the list's first item, so it starts at half the
 * screen and scrolls away as the tracks are scrolled up, the same as a playlist's
 * own screen. Tap the centre cover to open the full playlist; hold it for the
 * Play / Add / Share / Remove options.
 *
 * Every swap-driven effect (cover scale, title and track-list fade) reads the
 * pager's live position, so it moves with the finger rather than playing once the
 * swipe has settled. The list swaps its content when the pager's current page flips,
 * which is exactly when the fade hits zero, so the swap itself is never seen.
 */
@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    navController: NavHostController,
    active: Boolean = true,
) {
    val player = LocalPlayer.current
    val playlists by vm.favoritesTabPlaylists.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState { playlists.size }
    val playlist = playlists.getOrNull(pagerState.currentPage.coerceAtMost(playlists.lastIndex))
    val (sort, tracks) = rememberSortedTracks(vm, playlist)

    var optionsFor by remember { mutableStateOf<Playlist?>(null) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    // Lives here, not in the track sheet: the sheet closes as the editor opens.
    val tagEditor = rememberTrackTagEditor(vm)

    SwapTicks(pagerState)
    OpenOnLastPlayed(vm, pagerState)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val carouselHeight = maxHeight * 0.5f
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            // Clears the global mini player floating over the bottom edge.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "carousel") {
                PlaylistCarousel(
                    playlists = playlists,
                    pagerState = pagerState,
                    tabActive = active,
                    onOpen = { navController.navigate(Routes.playlist(it.id)) },
                    onLongPress = { optionsFor = it },
                    modifier = Modifier.height(carouselHeight),
                )
            }
            if (playlist != null) {
                item(key = "sort") { FavoriteSortHeader(vm, playlist, tracks, sort) }
                trackItems(
                    playlist = playlist,
                    tracks = tracks,
                    pagerState = pagerState,
                    onPlay = { index ->
                        player.playQueue(tracks, index, playlist.name)
                        vm.setLastPlayedPlaylist(playlist.id)
                        navController.navigate(Routes.NOW_PLAYING)
                    },
                    onMore = { sheetTrack = it },
                )
            }
        }
    }

    optionsFor?.let { pl ->
        FavoriteOptionsSheet(playlist = pl, vm = vm, onDismiss = { optionsFor = null })
    }
    if (playlist != null) {
        sheetTrack?.let { t ->
            FavoriteTrackActions(
                vm = vm,
                playlist = playlist,
                track = t,
                onEditTags = { tagEditor.open(t) },
                onDismiss = { sheetTrack = null },
            )
        }
    }
}

/**
 * The [playlist]'s tracks, sorted by its saved per-playlist sort, plus that sort.
 *
 * vm.playlistSort() starts a collector per call (see its doc comment), so one flow
 * per playlist is kept for this screen's lifetime instead of one per swipe.
 */
@Composable
private fun rememberSortedTracks(
    vm: AppViewModel,
    playlist: Playlist?,
): Pair<SortOption, List<Track>> {
    val allTracks by vm.tracks.collectAsStateWithLifecycle()
    val sortFlows = remember { mutableMapOf<String, StateFlow<SortOption>>() }
    val noPlaylistSort = remember { MutableStateFlow(DEFAULT_PLAYLIST_SORT) }
    val sortFlow =
        playlist?.let { pl -> sortFlows.getOrPut(pl.id) { vm.playlistSort(pl.id, DEFAULT_PLAYLIST_SORT) } }
    val sort by (sortFlow ?: noPlaylistSort).collectAsStateWithLifecycle()
    val tracks =
        remember(playlist, allTracks, sort) {
            playlist?.let { vm.sortPlaylistTracks(it.id, vm.tracksByIds(it.trackIds), sort) }.orEmpty()
        }
    return sort to tracks
}

@Composable
private fun FavoriteSortHeader(
    vm: AppViewModel,
    playlist: Playlist,
    tracks: List<Track>,
    sort: SortOption,
) {
    val player = LocalPlayer.current

    fun play(queue: List<Track>) {
        if (queue.isEmpty()) return
        player.playQueue(queue, 0, playlist.name)
        vm.setLastPlayedPlaylist(playlist.id)
    }
    SortHeader(
        current = sort,
        onSort = { vm.setPlaylistSort(playlist.id, it) },
        onShuffle = { play(tracks.shuffled()) },
        onPlayAll = { play(tracks) },
        // Custom order is only set by dragging, which only a user playlist allows.
        options = if (playlist.isEditable) SortOption.entries else SortOption.entries - SortOption.CUSTOM_ORDER,
    )
}

/**
 * Opens the carousel on the playlist the user last played from rather than always on
 * "Favorite tracks". Once per screen state (saveable, so a tab swipe or rotation keeps
 * whatever the user has swiped to since). Falls back to the first cover if that
 * playlist is gone or no longer hearted. The playlists load asynchronously at app
 * start, so this waits briefly for the saved one to show up before giving up.
 */
@Composable
private fun OpenOnLastPlayed(
    vm: AppViewModel,
    pagerState: PagerState,
) {
    var applied by rememberSaveable { mutableStateOf(false) }
    if (applied) return
    LaunchedEffect(Unit) {
        val id = vm.lastPlayedPlaylistId()
        if (id != null) {
            withTimeoutOrNull(LAST_PLAYED_WAIT_MS) {
                val page =
                    vm.favoritesTabPlaylists
                        .first { list -> list.any { pl -> pl.id == id } }
                        .indexOfFirst { pl -> pl.id == id }
                // The pager's page count follows the composed list, which can trail
                // the flow by a frame; scrolling before it catches up would clamp.
                snapshotFlow { pagerState.pageCount }.first { it > page }
                if (page > 0) pagerState.scrollToPage(page)
            }
        }
        applied = true
    }
}

private const val LAST_PLAYED_WAIT_MS = 3_000L

/** A light tick each time the centre cover changes, so a swipe feels like it clicks into place. */
@Composable
private fun SwapTicks(pagerState: PagerState) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}

/**
 * Fade + sideways drift for everything that belongs to the current playlist: fully
 * gone at the half-way point of a swipe (when the current page flips), back in as
 * the swipe settles. Read inside graphicsLayer so a swipe only redraws, never
 * recomposes, the rows.
 */
private fun Modifier.followSwap(pagerState: PagerState): Modifier =
    graphicsLayer {
        val f = pagerState.currentPageOffsetFraction
        alpha = (1f - 2f * abs(f)).coerceIn(0f, 1f)
        translationX = -f * 2f * SwapDrift.toPx()
    }

private fun LazyListScope.trackItems(
    playlist: Playlist,
    tracks: List<Track>,
    pagerState: PagerState,
    onPlay: (index: Int) -> Unit,
    onMore: (Track) -> Unit,
) {
    if (tracks.isEmpty()) {
        item(key = "empty-${playlist.id}") {
            Text(
                if (playlist.kind == PlaylistKind.FAVORITE_TRACKS) {
                    "Heart a track and it shows up here"
                } else {
                    "No tracks in this playlist yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).followSwap(pagerState),
            )
        }
    }
    // Deliberately unkeyed (keyed by position): on a playlist swap each visible row
    // just recomposes with its new track. Keying by track would throw every visible
    // row away and build fresh ones in the single frame the swap lands on, which
    // showed up as a dropped-frame hitch mid-swipe.
    itemsIndexed(tracks) { index, track ->
        TrackRow(
            track = track,
            modifier = Modifier.followSwap(pagerState),
            onClick = { onPlay(index) },
            onMore = { onMore(track) },
        )
    }
}

/**
 * The round-cover carousel plus the selected playlist's name. The centre cover is
 * full size; its neighbours shrink, dim and tuck in toward it so a slice of each
 * peeks in from the screen edges. Each page's tap target stays full size (only the
 * drawing shrinks), so the whole strip left or right of the centre cover is a
 * "previous" / "next" button.
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCarousel(
    playlists: List<Playlist>,
    pagerState: PagerState,
    tabActive: Boolean,
    onOpen: (Playlist) -> Unit,
    onLongPress: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // A record only turns while music plays; paused, it holds its angle.
    val isPlaying by LocalPlayer.current.isPlaying.collectAsStateWithLifecycle()
    val spinning = tabActive && isPlaying
    // One soft spring for both a released swipe and a tapped neighbour, so the two
    // land with the same feel.
    val settle = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val diameter = min(maxWidth * 0.6f, maxHeight - TitleBlock - 16.dp).coerceAtLeast(96.dp)
        val sidePadding = (maxWidth - diameter) / 2

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(diameter),
                contentPadding = PaddingValues(horizontal = sidePadding),
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = settle),
                key = { playlists[it].id },
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                val pl = playlists[page]
                CarouselCover(
                    playlist = pl,
                    // Signed distance from the centre slot: 0 = centred, ±1 = one page
                    // away (+ is to the right). Read at draw time, see CarouselCover.
                    offset = { (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction },
                    diameter = diameter,
                    spinning = spinning,
                    modifier =
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (page == pagerState.currentPage) {
                                    onOpen(pl)
                                } else {
                                    scope.launch { pagerState.animateScrollToPage(page, animationSpec = settle) }
                                }
                            },
                            onLongClick = { if (page == pagerState.currentPage) onLongPress(pl) },
                        ),
                )
            }
            CarouselTitle(playlists.getOrNull(pagerState.currentPage), pagerState)
        }
    }
}

@Composable
private fun CarouselCover(
    playlist: Playlist,
    offset: () -> Float,
    diameter: Dp,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val spin = rememberDiscSpin(offset, spinning)
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer {
                    val delta = offset()
                    val t = abs(delta).coerceAtMost(1f)
                    val scale = lerp(1f, 0.66f, t)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(1f, 0.5f, t)
                    translationX = -sign(delta) * t * size.width * 0.16f
                    shadowElevation = lerp(18.dp.toPx(), 0f, t)
                    shape = CircleShape
                    clip = true
                },
        ) {
            Artwork(
                uri = playlist.coverUri,
                corner = diameter / 2,
                iconSize = 64.dp,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.floatValue },
            )
        }
    }
}

/** One full turn of the centre disc every this many seconds. */
private const val DISC_SECONDS_PER_TURN = 10f

/**
 * The cover's rotation in degrees. The centre disc turns at full speed; a cover
 * spins slower the further it is from the centre and is still once a whole page
 * away, so a swipe spins the incoming disc up and winds the outgoing one down
 * instead of starting or stopping it dead. The angle is only read at draw time,
 * so spinning redraws the cover but never recomposes it.
 */
@Composable
private fun rememberDiscSpin(
    offset: () -> Float,
    spinning: Boolean,
): MutableFloatState {
    val angle = remember { mutableFloatStateOf(0f) }
    // The frame loop asks for a new frame every vsync, so it must stop while the
    // Favorites tab is off-screen (it stays composed then; see HomeScreen) or the
    // whole app would keep redrawing at full refresh rate from any tab.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val focus = 1f - abs(offset()).coerceAtMost(1f)
                    val seconds = (now - last) / 1_000_000_000f
                    angle.floatValue = (angle.floatValue + seconds * focus * 360f / DISC_SECONDS_PER_TURN) % 360f
                }
                last = now
            }
        }
    }
    return angle
}

@Composable
private fun CarouselTitle(
    playlist: Playlist?,
    pagerState: PagerState,
) {
    Column(
        Modifier
            .height(TitleBlock)
            .padding(horizontal = 24.dp)
            .followSwap(pagerState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (playlist != null) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
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
}

/** A track row's "more" sheet: Add to playlist / Remove / Edit tags / Track details. */
@Composable
private fun FavoriteTrackActions(
    vm: AppViewModel,
    playlist: Playlist,
    track: Track,
    onEditTags: () -> Unit,
    onDismiss: () -> Unit,
) {
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    var addTo by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    when {
        addTo ->
            AddToPlaylistDialog(
                playlists = userPlaylists.filter { it.id != playlist.id },
                onPick = { picked ->
                    picked.forEach { pl -> vm.addTracksToPlaylist(pl.id, listOf(track.id)) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        details -> TrackDetailsDialog(track = track, onDismiss = onDismiss)
        else ->
            PlaylistTrackActionsSheet(
                track = track,
                onAddToPlaylist = { addTo = true },
                onRemoveFromPlaylist = { vm.removeTracksFromPlaylist(playlist.id, listOf(track.id)) },
                onDetails = { details = true },
                onDismiss = onDismiss,
                removeEnabled = playlist.isEditable,
                onEditTags =
                    if (TrackTagEditor.canEdit(track)) {
                        {
                            onDismiss()
                            onEditTags()
                        }
                    } else {
                        null
                    },
            )
    }
}

@Composable
private fun FavoriteOptionsSheet(
    playlist: Playlist,
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player = LocalPlayer.current
    PlaylistOptionsSheet(
        playlist = playlist,
        onPlay = {
            player.playQueue(vm.tracksByIds(playlist.trackIds), 0)
            vm.setLastPlayedPlaylist(playlist.id)
        },
        onAdd = {
            player.playQueue(vm.tracksByIds(playlist.trackIds), 0)
            vm.setLastPlayedPlaylist(playlist.id)
        },
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
        onDismiss = onDismiss,
    )
}

private fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction
