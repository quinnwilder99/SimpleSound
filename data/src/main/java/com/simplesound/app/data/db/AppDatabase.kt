package com.simplesound.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        FavoriteTrackEntity::class,
        PlayStatsEntity::class,
        CustomOrderEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun playlistTrackDao(): PlaylistTrackDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun playStatsDao(): PlayStatsDao

    abstract fun customOrderDao(): CustomOrderDao
}
