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

        // Restore the last played track so the persistent mini player reappears
        // immediately after an app restart. We use the persisted *snapshot*
        // (title/artist/uri) so the bar shows instantly even before the MediaStore
        // scan finishes loading the real library. The next observer upgrades the
        // snapshot to the full live [Track] once the library loads that id.
        val lastId = MusicRepository.lastPlayedTrackId()
        if (lastId >= 0L) {
            val snapshot = MusicRepository.lastPlayedTrack()
            val live = MusicRepository.trackById(lastId)
            player.restoreLastPlayedTrack(live ?: snapshot)
        }
        // Persist the full snapshot of every subsequently played track so the bar
        // survives the next restart with correct title/artist even offline.
        lifecycleScope.launch {
            player.lastPlayedTrack.collect { track ->
                MusicRepository.saveLastPlayedTrack(track)
            }
        }
        // Once the device library finishes loading, the persisted snapshot (which has
        // a placeholder duration/album) can be upgraded to the full live [Track] for
        // that id. This fixes the "disappears after restart" case where the last
        // played track was a real device audio file that wasn't in memory yet at
        // launch.
        lifecycleScope.launch {
            MusicRepository.tracks.combine(player.lastPlayedTrack) { lib, current ->
                val id = current?.id ?: lastId
                if (id >= 0L) lib.firstOrNull { it.id == id } else null
            }.collect { upgraded ->
                if (upgraded != null) player.restoreLastPlayedTrack(upgraded)
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
