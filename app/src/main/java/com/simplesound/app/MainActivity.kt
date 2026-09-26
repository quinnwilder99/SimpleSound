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
import com.simplesound.app.playback.PlayerController
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import com.simplesound.app.ui.navigation.SimpleSoundNavHost
import com.simplesound.app.ui.theme.SimpleSoundTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's single UI Activity. Playback state (queue, position, shuffle/repeat)
 * is owned, persisted and restored by PlaybackService itself -- this Activity only
 * connects [player] to it while visible and kicks off the library scan.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var player: PlayerController

    private val viewModel: AppViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Regardless of the grant result, attempt to load the library; without
            // audio access the repository leaves the stored library untouched.
            viewModel.loadDeviceLibrary(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

    private fun requiredPermissions(): Array<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
}
