package com.simplesound.app

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        // Always-dark app: light bar icons no matter what the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        requestPermissions.launch(requiredPermissions())
        holdSplashUntilReady()

        setContent {
            val accent by viewModel.accent.collectAsStateWithLifecycle()
            SimpleSoundTheme(accent = accent) {
                CompositionLocalProvider(LocalPlayer provides player) {
                    SimpleSoundNavHost(vm = viewModel)
                }
            }
        }
    }

    /**
     * Keeps the system splash screen up (by declining to draw) until the first frame
     * can show the real library and settings instead of defaults that snap into place
     * a moment later. Composition still runs meanwhile, so the wait overlaps with
     * building the UI rather than adding to it; [AppViewModel.startupReady] caps it.
     */
    private fun holdSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!viewModel.startupReady.value) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
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
