package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayStatsDao {
    @Query("SELECT * FROM play_stats")
    fun observeAll(): Flow<List<PlayStatsEntity>>

    @Query("SELECT * FROM play_stats")
    suspend fun getAll(): List<PlayStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayStatsEntity)

    /** Drops play stats for tracks no longer present after a library rescan. */
    @Query("DELETE FROM play_stats WHERE trackId NOT IN (:validTrackIds)")
    suspend fun removeMissing(validTrackIds: List<Long>)
}
