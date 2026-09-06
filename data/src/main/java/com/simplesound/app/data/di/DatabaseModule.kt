package com.simplesound.app.data.di

import android.content.Context
import androidx.room.Room
import com.simplesound.app.data.db.AppDatabase
import com.simplesound.app.data.db.CustomOrderDao
import com.simplesound.app.data.db.FavoriteDao
import com.simplesound.app.data.db.PlayStatsDao
import com.simplesound.app.data.db.PlaylistDao
import com.simplesound.app.data.db.PlaylistTrackDao
import com.simplesound.app.data.db.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        // Applied one by one rather than with a spread so a growing migration list
        // stays cheap. NO fallbackToDestructiveMigration() anywhere: if a schema
        // change ever ships without a matching Migration we want the loud crash on
        // launch (caught by AppDatabaseMigrationTest / QA), NOT a silent wipe of
        // the user's playlists and play history. See AppDatabase's KDoc.
        AppDatabase.MIGRATIONS.forEach { builder.addMigrations(it) }
        return builder.build()
    }

    @Provides
    fun provideTrackDao(db: AppDatabase): TrackDao = db.trackDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlaylistTrackDao(db: AppDatabase): PlaylistTrackDao = db.playlistTrackDao()

    @Provides
    fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlayStatsDao(db: AppDatabase): PlayStatsDao = db.playStatsDao()

    @Provides
    fun provideCustomOrderDao(db: AppDatabase): CustomOrderDao = db.customOrderDao()
}
