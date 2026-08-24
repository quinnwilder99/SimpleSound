package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Insert
    suspend fun insert(entity: PlaylistEntity)

    @Update
    suspend fun update(entity: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE playlists SET position = :position WHERE id = :id")
    suspend fun updatePosition(
        id: String,
        position: Int,
    )
}
