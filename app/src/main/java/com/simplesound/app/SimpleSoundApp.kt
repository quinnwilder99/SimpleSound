package com.simplesound.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.sync.MediaStoreObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application singleton. [MusicRepository] and [MediaStoreObserver] are field-
 * injected (rather than just referenced where needed) specifically to force Hilt
 * to construct them here: Hilt injects an `@HiltAndroidApp` Application's fields
 * before this class's own [onCreate] body runs, so by the time [onCreate] executes,
 * [musicRepository] has already restored playlists/favorites/play-stats from Room
 * (see its `init` block) and [mediaStoreObserver] is ready to be registered.
 */
@HiltAndroidApp
class SimpleSoundApp : Application(), ImageLoaderFactory, Configuration.Provider {
    @Inject lateinit var musicRepository: MusicRepository

    @Inject lateinit var mediaStoreObserver: MediaStoreObserver

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Auto-syncs the library in the background whenever the device's audio
        // media changes (new file copied in, one deleted, tags edited) without
        // the user having to reopen the app or pull to refresh.
        mediaStoreObserver.register()
    }

    /**
     * Provides the process-wide default Coil [ImageLoader] for loading
     * album art and the static PNG fallback placeholder.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).build()

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(hiltWorkerFactory)
                .build()
}
