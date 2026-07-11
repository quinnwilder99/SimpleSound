package com.simplesound.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.simplesound.app.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin bridge between Compose and the [PlaybackService]'s MediaController. Exposes
 * the currently playing track and play/pause state as observable flows, and simple
 * transport controls. Safe to construct once at the Activity level.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private val trackIndex = mutableMapOf<String, Track>()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive: StateFlow<Boolean> = _sleepTimerActive.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != null) _currentTrack.value = trackIndex[id]
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /** Play [tracks] starting at [startIndex], replacing the current queue. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        trackIndex.clear()
        val items = tracks.map { track ->
            trackIndex[track.id.toString()] = track
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.uri.ifBlank { Uri.EMPTY.toString() })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artistOrUnknown)
                        .setAlbumTitle(track.albumOrUnknown)
                        .apply { track.albumArtUri?.let { setArtworkUri(Uri.parse(it)) } }
                        .build()
                )
                .build()
        }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
        _currentTrack.value = tracks[startIndex.coerceIn(0, tracks.lastIndex)]
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    /** Set playback speed (0.25x–4.0x clamped). 1.0f is normal speed. */
    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 4.0f)
        _playbackSpeed.value = s
        controller?.playbackParameters = PlaybackParameters(s, 1.0f)
    }

    /**
     * Pause playback after [minutes]. Only one sleep timer is active at a time;
     * setting a new one cancels the previous. Pass 0 (or call [cancelSleepTimer])
     * to cancel.
     */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        _sleepTimerActive.value = true
        val r = Runnable {
            controller?.pause()
            _sleepTimerActive.value = false
            sleepRunnable = null
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, minutes * 60_000L)
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        _sleepTimerActive.value = false
    }
}
