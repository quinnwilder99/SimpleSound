package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's manually dragged track order for one playlist, stored as the same
 * compact comma-joined id list `MusicRepository` has always used — only the
 * storage backend changed (Room column instead of a SharedPreferences blob). Absent
 * for a playlist that has never been custom-ordered; that playlist falls back to
 * its natural [PlaylistTrackCrossRef] order.
 */
@Entity(tableName = "custom_track_order")
data class CustomOrderEntity(
    @PrimaryKey val playlistId: String,
    val orderedTrackIdsCsv: String,
)
