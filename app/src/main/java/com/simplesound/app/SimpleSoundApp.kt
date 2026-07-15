package com.simplesound.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.simplesound.app.data.SettingsStore

/**
 * Application singleton. Holds the process-wide [SettingsStore] and
 * provides the Coil [ImageLoader] used across the app. Album-art
 * fallbacks are static PNGs, so the default decoders suffice.
 */
class SimpleSoundApp : Application(), ImageLoaderFactory {

    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
    }

    /**
     * Provides the process-wide default Coil [ImageLoader] for loading
     * album art and the static PNG fallback placeholder.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).build()

    companion object {
        lateinit var instance: SimpleSoundApp
            private set
    }
}
