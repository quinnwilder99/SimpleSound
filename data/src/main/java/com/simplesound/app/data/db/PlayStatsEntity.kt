package com.simplesound.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-track play stats, keyed by the track's stable MediaStore id. Kept in its own
 * table (separate from [TrackEntity]) because the track table is replaced wholesale
 * on every library rescan (see `TrackDao.replaceAll`) — without this side table,
 * every rescan would silently wipe out play counts and "Recently played"/
 * "Most played" would always be empty.
 */
@Entity(tableName = "play_stats")
data class PlayStatsEntity(
    @PrimaryKey val trackId: Long,
    val playCount: Int,
    val lastPlayedSec: Long,
)
