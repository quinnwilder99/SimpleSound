package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CustomOrderDao {
    @Query("SELECT * FROM custom_track_order")
    suspend fun getAll(): List<CustomOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomOrderEntity)

    @Query("DELETE FROM custom_track_order WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: String)
}
