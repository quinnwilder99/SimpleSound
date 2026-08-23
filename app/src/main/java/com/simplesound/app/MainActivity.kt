package com.simplesound.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.playback.PlayerController
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.navigation.SimpleSoundNavHost
import com.simplesound.app.ui.theme.SimpleSoundTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerController

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory((application as SimpleSoundApp).settingsStore)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Regardless of the grant result, attempt to load the library. If audio
            // access was denied, the repository keeps its sample data.
            viewModel.loadDeviceLibrary(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = PlayerController(applicationContext)

        // Restore the most recent playback state. We prefer restoring the *full
        // temp queue* (so the queue sheet + next/previous work), and fall back to
        // restoring a single last-played track when no queue was saved or the
        // saved playing id isn't part of the resolved queue. We use persisted
        // snapshots so the UI shows instantly even before the MediaStore scan
        // finishes loading the real library.
        val lastId = MusicRepository.lastPlayedTrackId()
        val savedPosition = MusicRepository.lastPlayedPosition()
        val queueTitle = MusicRepository.lastQueueTitle()
        val queueIds = MusicRepository.lastQueueTrackIds()
        val queueIndex = MusicRepository.lastQueueIndex()

        // Resolves the persisted queue ids against the current in-memory library
        // and, if they match, restores the full queue. Returns true on success.
        fun tryRestoreQueue(): Boolean {
            if (queueIds.isEmpty() || queueIndex < 0) return false
            val resolved = MusicRepository.tracksByIds(queueIds)
            if (resolved.isEmpty()) return false
            val playing = resolved.firstOrNull { it.id == lastId } ?: resolved.getOrNull(queueIndex)
                ?: return false
            val idx = resolved.indexOf(playing)
            player.restoreQueue(resolved, idx, queueTitle, savedPosition)
            return true
        }

        // On a cold start (e.g. after the OS killed the process when the task was
        // swiped away while paused) the MediaStore scan hasn't run yet, so the
        // library is still the sample seed and tracksByIds() above resolves
        // nothing -- this always misses on a true cold start. Retry once the
        // real device library finishes loading (see the "upgrade" collector
        // below for the single-track equivalent), but only if nothing has
        // already populated the queue in the meantime (either this retry
        // succeeding once, or the user starting playback of their own).
        var restoredQueue = tryRestoreQueue()
        if (!restoredQueue) {
            lifecycleScope.launch {
                MusicRepository.tracks.collect {
                    // Bail if the queue got populated another way already, or if
                    // the user has since started actual playback (e.g. of the
                    // single-track fallback below) -- don't clobber that with a
                    // stale restore.
                    if (restoredQueue || player.queue.value.isNotEmpty() || player.isPlaying.value) {
                        return@collect
                    }
                    if (tryRestoreQueue()) restoredQueue = true
                }
            }
        }
        if (!restoredQueue && lastId >= 0L) {
            val snapshot = MusicRepository.lastPlayedTrack()
            val live = MusicRepository.trackById(lastId)
            // Pass the persisted position so the timeline + resume point are
            // restored immediately. autoPrepareAndPause loads the track into the
            // Media3 controller (paused) so tapping Play resumes from this spot.
            player.restoreLastPlayedTrack(live ?: snapshot, savedPosition)
        }
        // Persist the full snapshot of every subsequently played track so the bar
        // survives the next restart with correct title/artist even offline. We
        // also persist the current playback position so playback can resume.
        lifecycleScope.launch {
            player.lastPlayedTrack.collect { track ->
                val pos = player.positionMs.value
                MusicRepository.saveLastPlayedTrack(track, pos)
            }
        }
        // Persist the temp queue whenever it changes so the queue sheet +
        // next/previous survive the next restart. We save the queue title, the
        // ordered track ids, and the currently playing index.
        lifecycleScope.launch {
            combine(player.queue, player.queueIndex, player.queueTitle) { tracks, index, title ->
                Triple(tracks, index, title)
            }.collect { (tracks, index, title) ->
                if (tracks.isEmpty()) {
                    MusicRepository.saveQueue(null, emptyList(), -1)
                } else {
                    val ids = tracks.map { it.id }
                    MusicRepository.saveQueue(title, ids, index)
                }
            }
        }
        // Once the device library finishes loading, the persisted snapshot (which has
        // a placeholder duration/album) can be upgraded to the full live [Track] for
        // that id. This fixes the "disappears after restart" case where the last
        // played track was a real device audio file that wasn't in memory yet at
        // launch. We don't override the saved position here - the controller may
        // already have one from the snapshot restore above.
        lifecycleScope.launch {
            MusicRepository.tracks.combine(player.lastPlayedTrack) { lib, current ->
                val id = current?.id ?: lastId
                if (id >= 0L) lib.firstOrNull { it.id == id } else null
            }.collect { upgraded ->
                if (upgraded != null) {
                    // Reuse the position the controller already has (either the
                    // restored snapshot position or the live playback position),
                    // and do NOT auto-prepare again if the player already has media
                    // items - otherwise we'd reload the track and reset play state.
                    val pos = player.positionMs.value
                    player.restoreLastPlayedTrack(upgraded, pos, autoPrepareAndPause = false)
                }
            }
        }

        requestPermissions.launch(requiredPermissions())

        setContent {
            val accent by viewModel.accent.collectAsStateWithLifecycle()
            SimpleSoundTheme(accent = accent) {
                CompositionLocalProvider(LocalPlayer provides player) {
                    SimpleSoundNavHost(vm = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onStop() {
        super.onStop()
        // Persist the current playback position before releasing the controller
        // so playback can resume from the correct spot after an app restart.
        // Without this, the saved position would only be updated on track
        // transitions, which is why the mini player reset to 00:00 after
        // closing+reopening the app mid-track.
        val track = player.lastPlayedTrack.value
        val pos = player.positionMs.value
        MusicRepository.saveLastPlayedTrack(track, pos)
        // Persist the queue snapshot too so the queue sheet survives restart. If
        // the queue is empty, clear the saved queue so we don't restore stale data.
        val tracks = player.queue.value
        if (tracks.isEmpty()) {
            MusicRepository.saveQueue(null, emptyList(), -1)
        } else {
            MusicRepository.saveQueue(
                player.queueTitle.value,
                tracks.map { it.id },
                player.queueIndex.value
            )
        }
        player.release()
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}