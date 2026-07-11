package com.simplesound.app.ui

import androidx.compose.runtime.compositionLocalOf
import com.simplesound.app.playback.PlayerController

/** Provides the shared [PlayerController] to the whole composition tree. */
val LocalPlayer = compositionLocalOf<PlayerController> {
    error("PlayerController not provided")
}
