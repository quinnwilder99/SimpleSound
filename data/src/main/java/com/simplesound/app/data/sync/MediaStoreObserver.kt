package com.simplesound.app.data.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI] for changes (a file added,
 * removed, or edited by any app — the Files app, a download, a sync client) and
 * enqueues [LibrarySyncWorker] to bring Room back in sync, so the library updates
 * itself without the user reopening SimpleSound or pulling to refresh.
 *
 * Registered once, from [com.simplesound.app.SimpleSoundApp.onCreate]. Registering
 * a [ContentObserver] doesn't itself require the read-audio permission — only the
 * eventual MediaStore query does, which [LibrarySyncWorker] guards separately.
 */
@Singleton
class MediaStoreObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var registered = false

        private val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = enqueueSync()
            }

        fun register() {
            if (registered) return
            registered = true
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                // notifyForDescendants =
                true,
                observer,
            )
        }

        private fun enqueueSync() {
            // A short debounce so a burst of changes (e.g. copying a whole album, or a
            // sync client writing many files back to back) coalesces into one rescan
            // instead of one per file; REPLACE below then collapses any request that
            // arrives while one is already queued/running into the newest one.
            val request =
                OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                LibrarySyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
