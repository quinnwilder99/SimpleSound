package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    /** Uses the `dateAddedSec` index — the library's default sort order. */
    @Query("SELECT * FROM tracks ORDER BY dateAddedSec DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    /**
     * Wholesale replace — a MediaStore rescan produces a full snapshot of the
     * current device library, not a diff. Favorites/play-stats/playlist membership
     * live in separate tables keyed by track id, so they survive this untouched.
     */
    @Transaction
    suspend fun replaceAll(tracks: List<TrackEntity>) {
        deleteAll()
        insertAll(tracks)
    }

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
