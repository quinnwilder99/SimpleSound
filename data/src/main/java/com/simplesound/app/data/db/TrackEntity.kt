package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room-persisted mirror of a scanned [com.simplesound.app.data.model.Track]. Play
 * count / last-played time are deliberately NOT stored here — see [PlayStatsEntity].
 *
 * Indices cover every column the UI actually sorts, groups, or filters by at
 * library scale (Tracks tab sort, Albums/Artists/Folders grouping, Search): without
 * them, each of those becomes a full table scan once a library grows past a few
 * thousand rows.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index("title"),
        Index("artist"),
        Index("album"),
        Index("folder"),
        Index("dateAddedSec"),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String?,
    val folder: String,
    val dateAddedSec: Long,
)
