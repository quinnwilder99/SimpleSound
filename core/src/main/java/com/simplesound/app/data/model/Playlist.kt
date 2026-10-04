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
    /**
     * Sort key for the Favorites tab (highest on top). Set to epoch millis when the
     * user hearts the playlist, so new hearts land on top; "Change order" on that tab
     * reshuffles the existing stamps (see MusicRepository.reorderFavoritePlaylists).
     * 0 when unhearted.
     */
    val favoritedAt: Long = 0L,
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
