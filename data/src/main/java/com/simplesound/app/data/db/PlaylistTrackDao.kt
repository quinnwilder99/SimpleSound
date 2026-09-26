package com.simplesound.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistTrackDao {
    @Query("SELECT * FROM playlist_track_cross_ref WHERE playlistId = :playlistId ORDER BY position")
    fun observeForPlaylist(playlistId: String): Flow<List<PlaylistTrackCrossRef>>

    @Query("SELECT * FROM playlist_track_cross_ref ORDER BY playlistId, position")
    suspend fun getAll(): List<PlaylistTrackCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(refs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Callers must keep [ids] under SQLite's bound-variable limit (see MusicRepository.IN_CHUNK). */
    @Query("DELETE FROM playlist_track_cross_ref WHERE trackId IN (:ids)")
    suspend fun deleteByTrackIds(ids: List<Long>)

    /** Drops membership rows whose track is no longer known at all — see [FavoriteDao.purgeOrphans]. */
    @Query("DELETE FROM playlist_track_cross_ref WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun purgeOrphans()

    /** Overwrite one playlist's full membership + order in one go. */
    @Transaction
    suspend fun replaceForPlaylist(
        playlistId: String,
        trackIds: List<Long>,
    ) {
        deleteForPlaylist(playlistId)
        insertAll(trackIds.mapIndexed { index, id -> PlaylistTrackCrossRef(playlistId, id, index) })
    }
}
