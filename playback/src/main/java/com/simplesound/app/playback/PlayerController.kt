package com.simplesound.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
 *
 * The sleep timer state ([sleepTimerActive] / [sleepTimerRemainingMs]) is owned by
 * [PlaybackService] so the countdown keeps running while the app is backgrounded
 * (this controller is torn down in onStop). Set/cancel requests are forwarded to
 * the service via Intent.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private val trackIndex = mutableMapOf<String, Track>()

    /**
     * Set by [restoreQueue] when it runs before [connect] has produced a
     * [MediaController] (the normal case: [MainActivity] restores state in
     * onCreate, before onStart calls [connect]). [prepareRestoredQueue] silently
     * no-ops without a controller, so without this the queue would never
     * actually be loaded into the real player -- cleared once it succeeds.
     */
    private data class PendingQueueRestore(
        val tracks: List<Track>,
        val startIndex: Int,
        val positionMs: Long
    )
    private var pendingQueueRestore: PendingQueueRestore? = null

    /**
     * Builds the [MediaItem] fed to the [MediaController]/[MediaSession]. The track's
     * own content URI is stashed in [MediaMetadata.extras] so [TrackArtworkBitmapLoader]
     * can decode its *embedded* per-track picture for the lock-screen/notification
     * widget; [Track.albumArtUri] is passed as [MediaMetadata.artworkUri] only as the
     * album-level fallback when no embedded picture exists (see MediaStoreScanner's
     * "Artwork strategy" doc comment and ui/Artwork.kt, which follow the same order).
     */
    private fun buildMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply {
            putString(TrackArtworkBitmapLoader.KEY_TRACK_CONTENT_URI, track.uri)
        }
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri.ifBlank { Uri.EMPTY.toString() })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistOrUnknown)
                    .setAlbumTitle(track.albumOrUnknown)
                    .setExtras(extras)
                    .apply { track.albumArtUri?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
            )
            .build()
    }

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _queueTitle = MutableStateFlow("")
    val queueTitle: StateFlow<String> = _queueTitle.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _lastPlayedTrack = MutableStateFlow<Track?>(null)
    val lastPlayedTrack: StateFlow<Track?> = _lastPlayedTrack.asStateFlow()

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
        if (positionMs > 0) _positionMs.value = positionMs
        if (track.durationMs > 0) _durationMs.value = track.durationMs
        if (autoPrepareAndPause) prepareRestoredTrack(track, positionMs)
    }

    fun restoreQueue(
        tracks: List<Track>,
        startIndex: Int,
        sourceTitle: String,
        positionMs: Long = 0L
    ) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
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
        pendingQueueRestore = PendingQueueRestore(tracks, safeIndex, positionMs)
        prepareRestoredQueue(tracks, safeIndex, positionMs)
    }

    private fun prepareRestoredTrack(track: Track, positionMs: Long) {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        trackIndex[track.id.toString()] = track
        val item = buildMediaItem(track)
        c.setMediaItem(item, positionMs.coerceAtLeast(0L))
        c.prepare()
        c.playWhenReady = false
    }

    private fun prepareRestoredQueue(tracks: List<Track>, startIndex: Int, positionMs: Long) {
        val c = controller ?: return
        val items = tracks.map { track -> buildMediaItem(track) }
        c.setMediaItems(items, startIndex, positionMs.coerceAtLeast(0L))
        c.prepare()
        c.playWhenReady = false
        pendingQueueRestore = null
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** Owned by [PlaybackService] so it survives Activity onStop(). */
    val sleepTimerActive: StateFlow<Boolean> = PlaybackService.sleepTimerActive

    /** Owned by [PlaybackService] so it survives Activity onStop(). */
    val sleepTimerRemainingMs: StateFlow<Long> = PlaybackService.sleepTimerRemainingMs

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isShuffleOn = MutableStateFlow(false)
    val isShuffleOn: StateFlow<Boolean> = _isShuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateProgressPolling()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncCurrentItem()
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
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c.also { it.addListener(listener) }
            _isShuffleOn.value = controller?.shuffleModeEnabled == true
            _repeatMode.value = when (controller?.repeatMode) {
                Player.REPEAT_MODE_ONE -> 2
                Player.REPEAT_MODE_ALL -> 1
                else -> 0
            }
            // Only overwrite the duration from the (possibly still-empty) real
            // controller when it actually has one -- a restoreQueue()/
            // restoreLastPlayedTrack() call earlier in onCreate (before this
            // future resolved) may have already primed it from persisted data,
            // and a fresh controller reports 0 until media is loaded below.
            controller?.duration?.takeIf { it > 0 }?.let { _durationMs.value = it }
            updateProgressPolling()
            // Prefer finishing a queue restore that couldn't run earlier because
            // the controller wasn't ready yet; fall back to the single-track path.
            val pendingQueue = pendingQueueRestore
            if (pendingQueue != null) {
                prepareRestoredQueue(pendingQueue.tracks, pendingQueue.startIndex, pendingQueue.positionMs)
            } else {
                maybePrepareRestoredTrack()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, sourceTitle: String = "") {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        trackIndex.clear()
        val items = tracks.map { track ->
            trackIndex[track.id.toString()] = track
            buildMediaItem(track)
        }
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        c.setMediaItems(items, safeIndex, 0L)
        c.prepare()
        c.play()
        _queue.value = tracks
        _queueTitle.value = sourceTitle
        _queueIndex.value = safeIndex
        _currentTrack.value = tracks[safeIndex]
        _lastPlayedTrack.value = tracks[safeIndex]
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0, "Queue")

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            maybePrepareRestoredTrack()
            c.play()
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun stop() {
        val c = controller ?: return
        _currentTrack.value = null
        _queueIndex.value = -1
        c.stop()
    }

    fun playQueueItemAt(index: Int) {
        val c = controller ?: return
        val q = _queue.value
        if (index !in q.indices) return
        c.seekToDefaultPosition(index)
        _queueIndex.value = index
        _currentTrack.value = q[index]
        c.play()
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        _queue.value = q
        val currentTrackId = _currentTrack.value?.id
        trackIndex.clear()
        val items = q.map { track ->
            trackIndex[track.id.toString()] = track
            buildMediaItem(track)
        }
        if (c != null) {
            val newIndex = q.indexOfFirst { it.id == currentTrackId }.takeIf { it >= 0 } ?: 0
            val pos = c.currentPosition.coerceAtLeast(0)
            c.setMediaItems(items, newIndex, pos)
            _queueIndex.value = newIndex
        }
    }

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

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        _positionMs.value = positionMs.coerceAtLeast(0)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.1f, 2.0f)
        _playbackSpeed.value = s
        controller?.playbackParameters = PlaybackParameters(s, 1.0f)
    }

    /**
     * Pause playback after [minutes]. Delegates to [PlaybackService] so the timer
     * keeps running while the app is in the background (this controller is torn
     * down in onStop). Pass 0 to cancel.
     */
    fun setSleepTimer(minutes: Int) {
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_SET_SLEEP_TIMER)
            .putExtra(PlaybackService.EXTRA_MINUTES, minutes)
        context.startService(intent)
    }

    fun cancelSleepTimer() {
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_CANCEL_SLEEP_TIMER)
        context.startService(intent)
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
                    // Belt-and-suspenders: if a track transition (e.g. an automatic
                    // end-of-track advance) somehow left the displayed track out of
                    // sync with the player, self-heal within one tick instead of
                    // staying stuck on the old title.
                    val id = c.currentMediaItem?.mediaId
                    if (id != null && _currentTrack.value?.id?.toString() != id) {
                        trackIndex[id]?.let { resolved ->
                            _currentTrack.value = resolved
                            _lastPlayedTrack.value = resolved
                            _queueIndex.value = c.currentMediaItemIndex
                        }
                    }
                    mainHandler.postDelayed(this, 500L)
                }
            }
            progressRunnable = r
            mainHandler.post(r)
        }
    }

    /**
     * Reconcile the displayed track, queue index, and timeline from the live
     * [controller] state. Reads directly from the controller rather than trusting
     * a listener callback's own parameters (e.g. [Player.Listener.onMediaItemTransition]'s
     * `mediaItem` argument), since those can be null or lag behind on an automatic
     * end-of-track advance — which previously left the timeline reset to 00:00
     * while the title/artist stayed on the just-finished track.
     */
    private fun syncCurrentItem() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId
        val resolved = id?.let { trackIndex[it] }
        if (resolved != null) {
            _currentTrack.value = resolved
            _lastPlayedTrack.value = resolved
        }
        _queueIndex.value = c.currentMediaItemIndex
        _positionMs.value = c.currentPosition.coerceAtLeast(0)
        _durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
    }

    private fun maybePrepareRestoredTrack() {
        val track = _lastPlayedTrack.value ?: return
        val c = controller ?: return
        // Note: _currentTrack.value is deliberately NOT checked here. restoreQueue()/
        // restoreLastPlayedTrack() always set it synchronously (so the UI has
        // something to show immediately), including when they run in onCreate
        // before connect() has produced a controller -- at that point
        // prepareRestoredTrack() silently no-ops for lack of one. Gating on
        // _currentTrack.value here would then make this a permanent no-op too,
        // leaving the real player with zero media items forever (play button
        // does nothing, position/duration stuck at 0). mediaItemCount is the
        // correct guard: it reflects the real controller, not just the flow.
        if (c.mediaItemCount > 0) return
        val pos = _positionMs.value.takeIf { it >= 0L } ?: -1L
        prepareRestoredTrack(track, pos)
    }

    companion object {
        const val ACTION_SET_SLEEP_TIMER = "com.simplesound.app.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.simplesound.app.CANCEL_SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"
    }
}