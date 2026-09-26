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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PlayStatsEntity>)

    @Query("DELETE FROM play_stats")
    suspend fun deleteAll()

    /** Callers must keep [ids] under SQLite's bound-variable limit (see MusicRepository.IN_CHUNK). */
    @Query("DELETE FROM play_stats WHERE trackId IN (:ids)")
    suspend fun removeAll(ids: List<Long>)

    /** Drops stats whose track is no longer known at all — see [FavoriteDao.purgeOrphans]. */
    @Query("DELETE FROM play_stats WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun purgeOrphans()
}
