package com.simplesound.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.model.EdgeBarSide
import com.simplesound.app.playback.LockScreenControlLauncher
import com.simplesound.app.playback.LockScreenPlaybackState
import com.simplesound.app.playback.LockScreenPlayerConnection
import com.simplesound.app.ui.screens.lockcontrol.LockControlScreen
import com.simplesound.app.ui.theme.SimpleSoundTheme
import com.simplesound.core.theme.AccentColor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The lock-screen "edge control bar" surface itself (see Settings > Edge
 * control bar). Shown over the real keyguard via [Activity.setShowWhenLocked],
 * triggered by `PlaybackService`'s `LockScreenControlLauncher` through a
 * full-screen notification intent -- not launched directly. See that class's
 * doc comment for the two earlier approaches that didn't survive testing on a
 * real device (a plain overlay window, then a direct `startActivity()` call).
 *
 * It intentionally does NOT try to reproduce the real lock screen (wallpaper,
 * notifications, emergency call) -- see [LockControlScreen]'s doc comment for
 * why. It drives playback through its own [LockScreenPlayerConnection] rather
 * than the app-wide [com.simplesound.app.playback.PlayerController] singleton:
 * that singleton's connect()/release() are tied to `MainActivity`'s
 * onStart/onStop, and `MainActivity` loses visibility (onStop) the moment this
 * activity shows over it -- sharing the controller would have this activity's
 * buttons go dead the instant it appears.
 *
 * `singleInstance` + finishing itself in `onStop` (any time it loses
 * visibility, for any reason) means at most one instance ever exists and it
 * never lingers in the background waiting to resurface stale -- the launcher
 * fires a fresh intent each qualifying screen-on instead.
 */
@AndroidEntryPoint
class LockScreenControlActivity : ComponentActivity() {
    @Inject lateinit var settingsStore: SettingsStore

    private val connection by lazy { LockScreenPlayerConnection(this) }
    private var playbackState by mutableStateOf(LockScreenPlaybackState(false, "", "", null, null))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The full-screen notification that launched us has done its job -- never let
        // it linger in the shade once this activity is actually up.
        LockScreenControlLauncher.cancelNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        setContent {
            val accent by settingsStore.accent.collectAsStateWithLifecycle(initialValue = AccentColor.Default)
            val side by settingsStore.edgeBarSide.collectAsStateWithLifecycle(initialValue = EdgeBarSide.Default)
            SimpleSoundTheme(accent = accent) {
                LockControlScreen(
                    state = playbackState,
                    side = side,
                    onDismiss = { finish() },
                    onPlayPause = { connection.togglePlayPause() },
                    onNext = { connection.next() },
                    onPrevious = { connection.previous() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect { state -> playbackState = state }
    }

    override fun onStop() {
        super.onStop()
        connection.release()
        // Never linger as a stale background instance -- see the class doc comment.
        // The launcher fires a fresh intent the next time this should show. Already
        // finishing (e.g. the user just tapped to dismiss) needs no second call.
        if (!isFinishing) finish()
    }
}
