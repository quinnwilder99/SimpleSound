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
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "simplesound.db").build()

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
