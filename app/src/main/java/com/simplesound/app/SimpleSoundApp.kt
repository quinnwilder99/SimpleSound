package com.simplesound.app

import android.app.Application
import com.simplesound.app.data.SettingsStore

/** Application singleton. Holds the process-wide [SettingsStore]. */
class SimpleSoundApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
    }

    companion object {
        lateinit var instance: SimpleSoundApp
            private set
    }
}
