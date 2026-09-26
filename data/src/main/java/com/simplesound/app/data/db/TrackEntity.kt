package com.simplesound.app.data.db

import androidx.room.ColumnInfo
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
 *
 * A track that disappears from MediaStore is NOT deleted from this table right
 * away: it is kept with a non-zero [missingSinceSec] so the user's favorites,
 * playlist membership and play stats for it survive an unmounted SD card or a
 * MediaStore re-index, and it is only purged after a grace period (see
 * [com.simplesound.app.data.reconcileLibrary]). [path] lets a re-indexed file that
 * came back under a new MediaStore id be matched to its old row.
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
    @ColumnInfo(defaultValue = "''") val path: String = "",
    /** Epoch seconds since this track was last seen in MediaStore; 0 = present. */
    @ColumnInfo(defaultValue = "0") val missingSinceSec: Long = 0L,
)
