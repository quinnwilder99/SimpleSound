package com.simplesound.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Everything `LockControlScreen` (:ui) needs to render, reported through
 * [LockScreenPlayerConnection.connect] -- no media3 types, so :app and :ui can
 * consume it without depending on media3 themselves.
 *
 * [embeddedArtSource] and [albumArtUri] follow the same artwork priority the
 * rest of the app uses (see [TrackArtworkBitmapLoader]'s doc comment and
 * [com.simplesound.app.ui.components.Artwork]): [embeddedArtSource] is the
 * track's own content URI, for its *embedded* picture (one track -> one
 * cover); [albumArtUri] is the album-level fallback used only when no
 * embedded picture exists.
 */
data class LockScreenPlaybackState(
    val isPlaying: Boolean,
    val trackTitle: String,
    val trackArtist: String,
    val embeddedArtSource: String?,
    val albumArtUri: String?,
)

/**
 * A minimal, independent [MediaController] connection to [PlaybackService], for
 * `LockScreenControlActivity` (:app) to drive its Play/Next/Previous buttons.
 *
 * Deliberately separate from [com.simplesound.app.playback.PlayerController]
 * (the app-wide singleton `MainActivity` uses): that one's connect()/release()
 * are tied to `MainActivity`'s onStart/onStop, and `MainActivity` loses
 * visibility (onStop) the instant the lock-screen control activity shows over
 * it -- sharing the controller would go dead right when it's needed.
 */
class LockScreenPlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var listener: Player.Listener? = null

    /** Connects and immediately reports the current state, then again on every
     *  play/pause or track change. */
    fun connect(onState: (LockScreenPlaybackState) -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c

            fun report() {
                val metadata = c.mediaMetadata
                onState(
                    LockScreenPlaybackState(
                        isPlaying = c.isPlaying,
                        trackTitle = metadata.title?.toString().orEmpty(),
                        trackArtist = metadata.artist?.toString().orEmpty(),
                        embeddedArtSource = metadata.extras?.getString(TrackArtworkBitmapLoader.KEY_TRACK_CONTENT_URI),
                        albumArtUri = metadata.artworkUri?.toString(),
                    ),
                )
            }

            val l =
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = report()

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = report()
                }
            listener = l
            c.addListener(l)
            report()
        }, ContextCompat.getMainExecutor(context))
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() = controller?.seekToPreviousMediaItem()

    fun release() {
        listener?.let { controller?.removeListener(it) }
        controller?.release()
        controller = null
        listener = null
    }
}
