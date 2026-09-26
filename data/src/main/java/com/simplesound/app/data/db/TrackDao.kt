package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TrackDao {
    /** Every row, including tracks currently missing from MediaStore. */
    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<TrackEntity>

    /** Tracks currently on the device. Uses the `dateAddedSec` index — the library's default sort order. */
    @Query("SELECT * FROM tracks WHERE missingSinceSec = 0 ORDER BY dateAddedSec DESC")
    suspend fun getPresent(): List<TrackEntity>

    @Query("SELECT id FROM tracks WHERE missingSinceSec != 0")
    suspend fun getMissingIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    /**
     * Wholesale replace with the reconciled snapshot built by
     * [com.simplesound.app.data.reconcileLibrary] (present tracks + tracks still
     * inside their missing grace period). Favorites/play-stats/playlist membership
     * live in separate tables keyed by track id, so they survive this untouched.
     */
    @Transaction
    suspend fun replaceAll(tracks: List<TrackEntity>) {
        deleteAll()
        insertAll(tracks)
    }

    /** Callers must keep [ids] under SQLite's bound-variable limit (see MusicRepository.IN_CHUNK). */
    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
