package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Join table for playlist membership: which tracks belong to which playlist, and
 * in what order ([position]). Replaces the old comma-joined `trackIds` string that
 * used to live inside the playlist's own SharedPreferences record.
 */
@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: Long,
    val position: Int,
)
