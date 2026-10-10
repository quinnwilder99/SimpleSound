package com.simplesound.app.data.model

/**
 * A playlist. User playlists are freely editable (name, cover, tracks). The four
 * "native" playlists (Recently added, Most played, Recently played, Favorite
 * tracks) are computed and cannot be deleted or renamed — see [PlaylistKind].
 */
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<Long> = emptyList(),
    val coverUri: String? = null,
    val kind: PlaylistKind = PlaylistKind.USER,
    /** Whether the user "hearted" this playlist (shows in the Favorites tab). */
    val favorited: Boolean = false,
    /** Epoch millis when the user hearted the playlist; 0 when unhearted. */
    val favoritedAt: Long = 0L,
    /**
     * When the playlist was created, for the Playlists tab's "Date added" sort
     * (newest first). Epoch millis for playlists created since this was added;
     * playlists that existed before carry small backfilled values (1, 2, 3, ... in
     * their original creation order), so they still sort correctly and always
     * count as older than any newer one.
     */
    val createdAt: Long = 0L,
) {
    val trackCount: Int get() = trackIds.size
    val isEditable: Boolean get() = kind == PlaylistKind.USER
}

enum class PlaylistKind {
    USER,
    FAVORITE_TRACKS, // auto-collects every hearted single track
    RECENTLY_ADDED,
    MOST_PLAYED,
    RECENTLY_PLAYED,
}
