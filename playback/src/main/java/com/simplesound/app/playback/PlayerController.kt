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

    // ---- Progress / timeline ----
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // ---- Shuffle / repeat ----
    private val _isShuffleOn = MutableStateFlow(false)
    val isShuffleOn: StateFlow<Boolean> = _isShuffleOn.asStateFlow()

    /**
     * 0 = off, 1 = repeat all, 2 = repeat one (A-B style track loop).
     */
    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateProgressPolling()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != null) _currentTrack.value = trackIndex[id]
            _positionMs.value = 0L
            _durationMs.value = controller?.duration?.takeIf { it > 0 } ?: 0L
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                _durationMs.value = controller?.duration?.takeIf { it > 0 } ?: 0L
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _isShuffleOn.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> 2
                Player.REPEAT_MODE_ALL -> 1
                else -> 0
            }
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            // Sync initial state
            _isShuffleOn.value = controller?.shuffleModeEnabled == true
            _repeatMode.value = when (controller?.repeatMode) {
                Player.REPEAT_MODE_ONE -> 2
                Player.REPEAT_MODE_ALL -> 1
                else -> 0
            }
            _durationMs.value = controller?.duration?.takeIf { it > 0 } ?: 0L
            updateProgressPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
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

    /** Seek to [positionMs] within the current track. */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        _positionMs.value = positionMs.coerceAtLeast(0)
    }

    /** Toggle shuffle on/off. */
    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /**
     * Cycle repeat mode: off -> repeat all -> repeat one (A-B) -> off.
     */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

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

    private fun updateProgressPolling() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        if (_isPlaying.value) {
            val r = object : Runnable {
                override fun run() {
                    val c = controller ?: return
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    val d = c.duration
                    if (d > 0) _durationMs.value = d
                    mainHandler.postDelayed(this, 500L)
                }
            }
            progressRunnable = r
            mainHandler.post(r)
        }
    }
}