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

    @Query("DELETE FROM favorite_tracks WHERE trackId = :trackId")
    suspend fun remove(trackId: Long)

    @Query("DELETE FROM favorite_tracks WHERE trackId IN (:ids)")
    suspend fun removeAll(ids: List<Long>)
}
