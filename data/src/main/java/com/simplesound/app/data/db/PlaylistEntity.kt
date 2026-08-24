package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created playlist. Native (computed) playlists — Recently added, Most
 * played, Recently played, Favorite tracks — are never stored here; they're
 * derived live from [TrackEntity]/[PlayStatsEntity]/[FavoriteTrackEntity] on every
 * read, exactly as before this table existed.
 *
 * [position] is the user's drag-reordered position among their own playlists
 * (see `MusicRepository.reorderPlaylists`); track membership + order lives in
 * [PlaylistTrackCrossRef], not here.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverUri: String?,
    val favorited: Boolean,
    val favoritedAt: Long,
    val position: Int,
)
