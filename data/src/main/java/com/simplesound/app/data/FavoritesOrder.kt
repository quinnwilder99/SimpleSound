package com.simplesound.app.data

import com.simplesound.app.data.model.Playlist

/**
 * New [Playlist.favoritedAt] stamps that make the Favorites tab (sorted by
 * favoritedAt, highest first) show [hearted] in [orderedIds] order. Hearted
 * playlists missing from [orderedIds] keep their current relative order at the
 * end; ids that aren't hearted are ignored.
 *
 * The existing stamps are handed back out (highest first) rather than invented,
 * so a playlist hearted later still gets "now" and lands on top. Duplicate stamps
 * are nudged apart so the result is strictly ordered.
 */
internal fun reorderedFavoriteStamps(
    hearted: List<Playlist>,
    orderedIds: List<String>,
): Map<String, Long> {
    val current = hearted.sortedByDescending { it.favoritedAt }
    val heartedIds = current.map { it.id }.toSet()
    val requested = orderedIds.filter { it in heartedIds }.distinct()
    val order = requested + current.map { it.id }.filterNot { it in requested }
    var previous = Long.MAX_VALUE
    return order.zip(current.map { it.favoritedAt }).associate { (id, stamp) ->
        previous = minOf(stamp, previous - 1)
        id to previous
    }
}
