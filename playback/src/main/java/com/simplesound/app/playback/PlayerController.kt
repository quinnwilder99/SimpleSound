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

    // ---- Temp queue ----
    // The ordered list of tracks currently loaded into the player, plus the index
    // of the playing item. This is a *temp* queue: reordering it here does not
    // modify the source playlist/all-tracks list it was built from.
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _queueTitle = MutableStateFlow("")
    val queueTitle: StateFlow<String> = _queueTitle.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    /**
     * The most recently played track. Persists across pause/stop so the mini player
     * can keep showing the last played title even when [currentTrack] is cleared.
     */
    private val _lastPlayedTrack = MutableStateFlow<Track?>(null)
    val lastPlayedTrack: StateFlow<Track?> = _lastPlayedTrack.asStateFlow()

    /**
     * Restore the last played track from persistence (e.g. after app restart).
     * Only seeds the displayed value if no track is currently set, so it never
     * clobbers a live session.
     *
     * When [autoPrepareAndPause] is true (the default) and a MediaController is
     * connected, the track is also loaded into the Media3 player and prepared in
     * a *paused* state at [positionMs]. This makes the "Play" button resume from
     * the saved spot without rebuilding the whole queue. Pass `false` when the
     * caller only wants to upgrade the displayed track snapshot (e.g. once the
     * live library has loaded) without disturbing existing playback state.
     */
    fun restoreLastPlayedTrack(
        track: Track?,
        positionMs: Long = 0L,
        autoPrepareAndPause: Boolean = true
    ) {
        if (track == null) return
        if (_lastPlayedTrack.value == null) _lastPlayedTrack.value = track
        if (_currentTrack.value == null) {
            _currentTrack.value = track
            trackIndex[track.id.toString()] = track
        }
        // Surface the restored position/duration immediately so the timeline shows
        // the correct progress bar before the controller is connected.
        if (positionMs > 0) _positionMs.value = positionMs
        if (track.durationMs > 0) _durationMs.value = track.durationMs
        if (autoPrepareAndPause) preparePaused(track, positionMs)
    }

    /**
     * Restore a full temp queue from persistence (e.g. after app restart). The
     * [tracks] are loaded into the Media3 player in order, with the playing window
     * set to [startIndex] and the cursor moved to [positionMs]. The player is
     * prepared but left *paused* so the user must press Play to resume — we never
     * auto-resume playback on launch. Also seeds [lastPlayedTrack] /
     * [currentTrack] / the queue flows so the UI reflects the restored state
     * immediately. Safe to call before [connect]; the prepare is a no-op until a
     * controller attaches (callers may re-invoke once connected if needed).
     */
    fun restoreQueue(
        tracks: List<Track>,
        startIndex: Int,
        sourceTitle: String,
        positionMs: Long = 0L
    ) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        // Seed trackIndex for every item so onMediaItemTransition can resolve ids.
        trackIndex.clear()
        tracks.forEach { trackIndex[it.id.toString()] = it }
        _queue.value = tracks
        _queueTitle.value = sourceTitle
        _queueIndex.value = safeIndex
        _currentTrack.value = tracks[safeIndex]
        _lastPlayedTrack.value = tracks[safeIndex]
        if (positionMs > 0) _positionMs.value = positionMs
        val dur = tracks[safeIndex].durationMs
        if (dur > 0) _durationMs.value = dur
        // Load into Media3 (paused) so Play resumes from the saved spot.
        prepareQueuePaused(tracks, safeIndex, positionMs)
    }

    /**
     * Load [track] into the Media3 controller as a single-item queue and prepare
     * it paused at [positionMs]. No-op if the controller is not connected yet.
     */
    private fun preparePaused(track: Track, positionMs: Long) {
        val c = controller ?: return
        trackIndex[track.id.toString()] = track
        val item = MediaItem.Builder()
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
        c.setMediaItem(item, positionMs.coerceAtLeast(0L))
        c.prepare()
        // Do not call play() — we want a paused resume point.
    }

    /**
     * Load [tracks] into the Media3 controller as a multi-item queue, start at
     * [startIndex], and prepare paused at [positionMs]. No-op if the controller
     * is not connected.
     */
    private fun prepareQueuePaused(tracks: List<Track>, startIndex: Int, positionMs: Long) {
        val c = controller ?: return
        val items = tracks.map { track ->
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
        c.setMediaItems(items, startIndex, positionMs.coerceAtLeast(0L))
        c.prepare()
        // Paused resume point — don't auto play on launch.
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive: StateFlow<Boolean> = _sleepTimerActive.asStateFlow()

    /**
     * Remaining milliseconds on the active sleep timer. Ticks down roughly every
     * second while a timer is running; 0 when no timer is active. UI uses this to
     * render a countdown clock.
     */
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

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
    private var sleepTicker: Runnable? = null
    private var sleepEndAtMs: Long = 0L
    private var progressRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateProgressPolling()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != null) {
                _currentTrack.value = trackIndex[id]
                _lastPlayedTrack.value = trackIndex[id]
            }
            // Keep the queue index in sync with the player's current window index.
            _queueIndex.value = controller?.currentMediaItemIndex ?: -1
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
            // The service may fail to bind (e.g. process death, permission
            // revocation). Guard `future.get()` so a connection failure cannot
            // crash the whole app; we simply leave controller == null and the
            // UI degrades to a non-playing state safely.
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c.also { it.addListener(listener) }
            // Sync initial state
            _isShuffleOn.value = controller?.shuffleModeEnabled == true
            _repeatMode.value = when (controller?.repeatMode) {
                Player.REPEAT_MODE_ONE -> 2
                Player.REPEAT_MODE_ALL -> 1
                else -> 0
            }
            _durationMs.value = controller?.duration?.takeIf { it > 0 } ?: 0L
            // If the controller attached *after* we already restored a queue/track
            // from persistence (onCreate runs before onStart/connect), the earlier
            // prepare was a no-op. Reload the restored media into Media3 now so the
            // saved position is honored when the user presses Play — otherwise the
            // controller has no media items and Play resets to 00:00. Skip this if
            // the service is already holding a live queue (mediaItemCount > 0) so
            // we don't clobber an active session.
            prepareRestoredIfNeeded()
            updateProgressPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        sleepTicker?.let { mainHandler.removeCallbacks(it) }
        sleepTicker = null
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /**
     * Play [tracks] starting at [startIndex], replacing the current queue.
     *
     * @param sourceTitle Optional label describing where this queue came from
     * (e.g. the playlist name, "All tracks", or "Queue"). Surfaced in the
     * Now Playing queue sheet so the user knows the context the track came from.
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, sourceTitle: String = "") {
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
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        c.setMediaItems(items, safeIndex, 0L)
        c.prepare()
        c.play()
        // Track the temp queue for the Now Playing queue sheet.
        _queue.value = tracks
        _queueTitle.value = sourceTitle
        _queueIndex.value = safeIndex
        _currentTrack.value = tracks[safeIndex]
        _lastPlayedTrack.value = tracks[safeIndex]
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0, "Queue")

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    /**
     * Stop playback entirely. The mini player keeps showing the last played track
     * title (via [lastPlayedTrack]), but the current track state is cleared so
     * the play/pause button reflects a stopped state.
     */
    fun stop() {
        val c = controller ?: return
        _currentTrack.value = null
        _queueIndex.value = -1
        c.stop()
    }

    /**
     * Jump to the queue item at [index] without rebuilding the queue. The temp
     * queue order is preserved; only the playing item changes.
     */
    fun playQueueItemAt(index: Int) {
        val c = controller ?: return
        val q = _queue.value
        if (index !in q.indices) return
        c.seekToDefaultPosition(index)
        _queueIndex.value = index
        _currentTrack.value = q[index]
        c.play()
    }

    /**
     * Move a queue item from [from] to [to] in the temp queue. The Media3
     * controller's media items are reordered to match; playback of the current
     * track continues uninterrupted. The "currently playing" index is updated to
     * follow the moved item if needed.
     */
    fun moveQueueItem(from: Int, to: Int) {
        val c = controller
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        _queue.value = q
        // Rebuild the Media3 queue at the new order while keeping the playing
        // window position correct.
        val currentTrackId = _currentTrack.value?.id
        trackIndex.clear()
        val items = q.map { track ->
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
        if (c != null) {
            val newIndex = q.indexOfFirst { it.id == currentTrackId }.takeIf { it >= 0 } ?: 0
            val pos = c.currentPosition.coerceAtLeast(0)
            c.setMediaItems(items, newIndex, pos)
            _queueIndex.value = newIndex
        }
    }

    /**
     * Remove the item at [index] from the temp queue. If it is the current item,
     * the player advances to the next available item (or stops if the queue is
     * emptied). Does not affect the source playlist/all-tracks list.
     */
    fun removeQueueItem(index: Int) {
        val c = controller
        val q = _queue.value.toMutableList()
        if (index !in q.indices) return
        val removed = q.removeAt(index)
        _queue.value = q
        trackIndex.remove(removed.id.toString())
        if (c != null) {
            c.removeMediaItem(index)
            if (q.isEmpty()) {
                _queueIndex.value = -1
                _currentTrack.value = null
                c.stop()
            } else {
                _queueIndex.value = c.currentMediaItemIndex
                val mid = c.currentMediaItem?.mediaId
                _currentTrack.value = if (mid != null) trackIndex[mid] else _currentTrack.value
            }
        }
    }

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
        val s = speed.coerceIn(0.1f, 2.0f)
        _playbackSpeed.value = s
        controller?.playbackParameters = PlaybackParameters(s, 1.0f)
    }

    /**
     * Pause playback after [minutes]. Only one sleep timer is active at a time;
     * setting a new one cancels the previous. Pass 0 (or call [cancelSleepTimer])
     * to cancel. While running, [sleepTimerRemainingMs] ticks down every second
     * so the UI can render a countdown clock.
     */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        val totalMs = minutes * 60_000L
        _sleepTimerActive.value = true
        _sleepTimerRemainingMs.value = totalMs
        sleepEndAtMs = System.currentTimeMillis() + totalMs

        // Final fire: pause playback once the duration elapses.
        val r = Runnable {
            controller?.pause()
            _sleepTimerActive.value = false
            _sleepTimerRemainingMs.value = 0L
            sleepRunnable = null
            sleepTicker?.let { mainHandler.removeCallbacks(it) }
            sleepTicker = null
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, totalMs)

        // Ticker: update remaining time roughly every second for the countdown UI.
        val t = object : Runnable {
            override fun run() {
                val remaining = sleepEndAtMs - System.currentTimeMillis()
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0L)
                if (!_sleepTimerActive.value) return
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        sleepTicker = t
        mainHandler.post(t)
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepTicker?.let { mainHandler.removeCallbacks(it) }
        sleepTicker = null
        _sleepTimerActive.value = false
        _sleepTimerRemainingMs.value = 0L
    }

    /**
     * Push the restored queue or single track into the just-attached Media3
     * controller (paused at the saved position). Called from [connect] once the
     * controller binds. No-op if the service is already playing something
     * (mediaItemCount > 0), or if nothing was restored.
     */
    private fun prepareRestoredIfNeeded() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val pos = _positionMs.value
        val q = _queue.value
        if (q.isNotEmpty() && _queueIndex.value >= 0) {
            val idx = _queueIndex.value.coerceIn(0, q.lastIndex)
            prepareQueuePaused(q, idx, pos)
        } else {
            val track = _lastPlayedTrack.value ?: return
            preparePaused(track, pos)
        }
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