package com.simplesound.app.data

import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.SortOption

/** The sorts the Playlists tab offers (and the Favorites tab follows). */
val PLAYLISTS_TAB_SORTS: List<SortOption> = listOf(SortOption.NAME, SortOption.DATE_ADDED, SortOption.CUSTOM_ORDER)

/** Until the user picks one: the drag order, which is how the tab always looked before it had a sort. */
val DEFAULT_PLAYLISTS_TAB_SORT: SortOption = SortOption.CUSTOM_ORDER

/**
 * [playlists] in [sort] order. [playlists] must already be in custom (drag) order,
 * which is how the repository publishes them; [SortOption.CUSTOM_ORDER] and any
 * sort the Playlists tab doesn't offer return them unchanged.
 */
fun sortPlaylists(
    playlists: List<Playlist>,
    sort: SortOption,
): List<Playlist> =
    when (sort) {
        SortOption.NAME -> playlists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        SortOption.DATE_ADDED -> playlists.sortedByDescending { it.createdAt }
        else -> playlists
    }
