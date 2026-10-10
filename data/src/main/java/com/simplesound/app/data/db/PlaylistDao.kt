package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    // REPLACE (not the @Insert default of ABORT) so the one-time legacy-prefs
    // migration in MusicRepository can safely re-run a partially-completed insert
    // loop after process death without throwing SQLiteConstraintException.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
