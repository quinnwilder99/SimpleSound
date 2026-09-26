package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT trackId FROM favorite_tracks")
    fun observeAll(): Flow<List<Long>>

    @Query("SELECT trackId FROM favorite_tracks")
    suspend fun getAll(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entity: FavoriteTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(entities: List<FavoriteTrackEntity>)

    @Query("DELETE FROM favorite_tracks WHERE trackId = :trackId")
    suspend fun remove(trackId: Long)

    /** Callers must keep [ids] under SQLite's bound-variable limit (see MusicRepository.IN_CHUNK). */
    @Query("DELETE FROM favorite_tracks WHERE trackId IN (:ids)")
    suspend fun removeAll(ids: List<Long>)

    @Query("DELETE FROM favorite_tracks")
    suspend fun deleteAll()

    /**
     * Drops favorites whose track is no longer known at all. A subquery rather than
     * a bound `NOT IN (:ids)` list: SQLite before 3.32 (Android 8–11) caps a
     * statement at 999 bound variables, so passing the whole library as a list
     * threw for any library over 999 tracks.
     */
    @Query("DELETE FROM favorite_tracks WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun purgeOrphans()
}
