package com.simplesound.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.playback.PlayerController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Provides the shared [PlayerController] to the whole composition tree. */
val LocalPlayer =
    compositionLocalOf<PlayerController> {
        error("PlayerController not provided")
    }

/**
 * The id of the track currently loaded in the player (null when nothing is), for
 * marking that track's row in a list.
 *
 * Narrowed to the id and de-duplicated, so it only emits when the player actually
 * moves to a different track: a tag edit or play-count refresh that swaps in an
 * updated Track object with the same id doesn't ripple through every list. Read
 * the returned state inside each list item (not in the LazyColumn content lambda)
 * so a track change only recomposes the visible rows, not the whole list.
 */
@Composable
fun rememberCurrentTrackId(): State<Long?> {
    val player = LocalPlayer.current
    val ids = remember(player) { player.currentTrack.map { it?.id }.distinctUntilChanged() }
    return ids.collectAsStateWithLifecycle(initialValue = player.currentTrack.value?.id)
}
