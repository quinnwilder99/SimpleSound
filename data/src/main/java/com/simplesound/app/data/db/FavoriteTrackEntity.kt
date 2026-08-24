package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single track the user has "hearted". Replaces the old comma-joined id set. */
@Entity(tableName = "favorite_tracks")
data class FavoriteTrackEntity(
    @PrimaryKey val trackId: Long,
)
