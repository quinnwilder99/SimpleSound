package com.simplesound.app.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplesound.app.data.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Re-scans the device's audio library and updates Room, run in the background by
 * [MediaStoreObserver] whenever the device's media changes — no user action
 * (reopening the app, pulling to refresh) required.
 */
@HiltWorker
class LibrarySyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val musicRepository: MusicRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Media access may have been revoked since the observer was registered
            // (e.g. the user pulled the permission in Settings). Skip quietly rather
            // than let the scan fail and trigger a retry loop.
            if (!hasAudioPermission()) return Result.success()
            return try {
                musicRepository.loadDeviceLibrary(applicationContext)
                Result.success()
            } catch (t: Throwable) {
                Result.retry()
            }
        }

        private fun hasAudioPermission(): Boolean {
            val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            return ContextCompat.checkSelfPermission(applicationContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        }

        companion object {
            const val UNIQUE_WORK_NAME = "library_sync"
        }
    }
