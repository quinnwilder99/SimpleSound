package com.simplesound.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin bridge between Compose and the [PlaybackService]'s MediaController. A
 * Hilt-provided singleton, connected in `MainActivity.onStart` and released in `onStop`.
 *
 * The service's player is the single source of truth: the queue, current track,
 * shuffle order and resume point all live there, and [PlaybackService] persists and
 * restores them itself (it keeps playing -- and advancing -- while this controller is
 * disconnected). The flows here just mirror the session:
 * - [queue] is always the player's items in playlist order; [playOrder] gives the
 *   order they will actually play in (differs when shuffle is on).
 * - Before the controller has connected, [lastPlayedTrack]/position/duration are
 *   seeded from the persisted snapshot so the mini player renders instantly.
 * - Commands issued before the connection completes (e.g. a tap in the first
 *   moments after launch) are queued and run once connected, instead of being
 *   silently dropped.
 *
 * The sleep timer state ([sleepTimerActive] / [sleepTimerRemainingMs]) is owned by
 * [PlaybackService] so the countdown keeps running while the app is backgrounded.
 */
@Singleton
class PlayerController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: MusicRepository,
    ) {
        private var controller: MediaController? = null
        private var controllerFuture: ListenableFuture<MediaController>? = null
        private val pendingActions = ArrayDeque<(MediaController) -> Unit>()

        private val _queue = MutableStateFlow<List<Track>>(emptyList())
        val queue: StateFlow<List<Track>> = _queue.asStateFlow()

        private val _playOrder = MutableStateFlow<List<Int>>(emptyList())

        /** [queue] indices in the order they will play; identical to 0..n-1 unless shuffle is on. */
        val playOrder: StateFlow<List<Int>> = _playOrder.asStateFlow()

        private val _queueTitle = MutableStateFlow("")
        val queueTitle: StateFlow<String> = _queueTitle.asStateFlow()

        private val _queueIndex = MutableStateFlow(-1)
        val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

        private val _currentTrack = MutableStateFlow<Track?>(null)
        val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

        private val _lastPlayedTrack = MutableStateFlow<Track?>(null)
        val lastPlayedTrack: StateFlow<Track?> = _lastPlayedTrack.asStateFlow()

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

        /** 0 = off, 1 = repeat all, 2 = repeat one. */
        private val _repeatMode = MutableStateFlow(0)
        val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var progressRunnable: Runnable? = null

        init {
            // Instant mini player on launch, before the session connects / restores.
            repository.lastPlayedTrack()?.let { snapshot ->
                _lastPlayedTrack.value = snapshot
                _durationMs.value = snapshot.durationMs.coerceAtLeast(0L)
                _positionMs.value = repository.lastPlayedPosition()
            }
            _isShuffleOn.value = repository.lastShuffleEnabled()
            _repeatMode.value = repository.lastRepeatMode()
        }

        private val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    updateProgressPolling()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    controller?.let { syncCurrentItem(it) }
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    controller?.let { syncQueue(it) }
                }

                override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
                    _queueTitle.value = mediaMetadata.title?.toString().orEmpty()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        controller?.duration?.takeIf { it > 0 }?.let { _durationMs.value = it }
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _isShuffleOn.value = shuffleModeEnabled
                    controller?.let { _playOrder.value = it.playOrder() }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value = repeatModeToApp(repeatMode)
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    _playbackSpeed.value = playbackParameters.speed
                }
            }

        fun connect() {
            if (controller != null || controllerFuture != null) return
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            future.addListener({
                // A release() in the meantime cancels/replaces the future; don't adopt it.
                if (controllerFuture !== future) return@addListener
                val c = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = c
                c.addListener(listener)
                _isShuffleOn.value = c.shuffleModeEnabled
                _repeatMode.value = repeatModeToApp(c.repeatMode)
                _isPlaying.value = c.isPlaying
                _playbackSpeed.value = c.playbackParameters.speed
                // The session may have moved on (or been restored by the service) while
                // we were disconnected; mirror whatever it holds now.
                syncQueue(c)
                while (pendingActions.isNotEmpty()) pendingActions.removeFirst().invoke(c)
                updateProgressPolling()
            }, ContextCompat.getMainExecutor(context))
        }

        fun release() {
            progressRunnable?.let { mainHandler.removeCallbacks(it) }
            progressRunnable = null
            pendingActions.clear()
            controller?.removeListener(listener)
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
            controller = null
        }

        /** Runs [action] now if connected, otherwise as soon as the connection completes. */
        private fun withController(action: (MediaController) -> Unit) {
            val c = controller
            if (c != null) action(c) else pendingActions.addLast(action)
        }

        fun playQueue(
            tracks: List<Track>,
            startIndex: Int = 0,
            sourceTitle: String = "",
        ) {
            if (tracks.isEmpty()) return
            val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
            // Optimistic UI update; syncQueue() confirms it from the session.
            _queue.value = tracks
            _playOrder.value = tracks.indices.toList()
            _queueTitle.value = sourceTitle
            _queueIndex.value = safeIndex
            _currentTrack.value = tracks[safeIndex]
            _lastPlayedTrack.value = tracks[safeIndex]
            _positionMs.value = 0L
            _durationMs.value = tracks[safeIndex].durationMs.coerceAtLeast(0L)
            val items = tracks.map { it.toMediaItem() }
            withController { c ->
                c.playlistMetadata = MediaMetadata.Builder().setTitle(sourceTitle).build()
                c.setMediaItems(items, safeIndex, 0L)
                c.prepare()
                c.play()
            }
        }

        fun playSingle(track: Track) = playQueue(listOf(track), 0, "Queue")

        /**
         * Restarts the currently loaded track from the beginning without touching
         * the queue. Used by the Now Playing screen's "..." > Play action, which
         * must NOT go through [playSingle]/[playQueue]: those replace the live
         * queue with a one-track queue, which silently strands the player on that
         * single track -- Next/Previous stop doing anything (there's nothing to
         * seek to) until the user starts fresh playback from a track list. See
         * the "..." menu wiring in NowPlayingScreen.
         */
        fun restartCurrentTrack() {
            _positionMs.value = 0L
            withController { c ->
                if (c.mediaItemCount == 0) return@withController
                c.seekTo(0L)
                c.play()
            }
        }

        /**
         * With an empty player (the service is still restoring the saved queue),
         * play() just sets playWhenReady; the restore keeps it, so playback starts
         * the moment the queue lands.
         */
        fun togglePlayPause() =
            withController { c ->
                if (c.isPlaying) c.pause() else c.play()
            }

        fun next() = withController { it.seekToNextMediaItem() }

        fun previous() = withController { it.seekToPreviousMediaItem() }

        fun stop() {
            _currentTrack.value = null
            _queueIndex.value = -1
            withController { it.stop() }
        }

        /** [index] is a [queue] (playlist-order) index. */
        fun playQueueItemAt(index: Int) {
            val q = _queue.value
            if (index !in q.indices) return
            _queueIndex.value = index
            _currentTrack.value = q[index]
            withController { c ->
                c.seekToDefaultPosition(index)
                c.play()
            }
        }

        fun moveQueueItem(
            from: Int,
            to: Int,
        ) {
            val q = _queue.value.toMutableList()
            if (from !in q.indices || to !in q.indices) return
            q.add(to, q.removeAt(from))
            _queue.value = q
            // Player.moveMediaItem reorders in place without rebuilding the timeline,
            // so the currently playing item isn't re-prepared/rebuffered.
            withController { it.moveMediaItem(from, to) }
        }

        fun removeQueueItem(index: Int) {
            val q = _queue.value
            if (index !in q.indices) return
            _queue.value = q.toMutableList().apply { removeAt(index) }
            withController { c ->
                c.removeMediaItem(index)
                if (c.mediaItemCount == 0) c.stop()
            }
        }

        /** Drops every queue entry for [trackIds] (used after those tracks were deleted). */
        fun removeTracksFromQueue(trackIds: Set<Long>) {
            if (trackIds.isEmpty()) return
            _queue.value = _queue.value.filterNot { it.id in trackIds }
            if (_currentTrack.value?.id in trackIds) _currentTrack.value = null
            if (_lastPlayedTrack.value?.id in trackIds) _lastPlayedTrack.value = null
            if (repository.lastPlayedTrackId() in trackIds) repository.saveLastPlayedTrack(null)
            withController { c ->
                for (i in c.mediaItemCount - 1 downTo 0) {
                    if (c.getMediaItemAt(i).mediaId.toLongOrNull() in trackIds) c.removeMediaItem(i)
                }
                if (c.mediaItemCount == 0) c.stop()
            }
        }

        fun seekTo(positionMs: Long) {
            val pos = positionMs.coerceAtLeast(0)
            _positionMs.value = pos
            withController { it.seekTo(pos) }
        }

        fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

        fun cycleRepeatMode() =
            withController { c ->
                c.repeatMode =
                    when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
            }

        private fun repeatModeToApp(mode: Int): Int =
            when (mode) {
                Player.REPEAT_MODE_ONE -> 2
                Player.REPEAT_MODE_ALL -> 1
                else -> 0
            }

        fun setSpeed(speed: Float) {
            val s = speed.coerceIn(0.1f, 2.0f)
            _playbackSpeed.value = s
            withController { it.playbackParameters = PlaybackParameters(s, 1.0f) }
        }

        /**
         * Pause playback after [minutes]. Delegates to [PlaybackService] so the timer
         * keeps running while the app is in the background (this controller is torn
         * down in onStop). Pass 0 to cancel.
         */
        fun setSleepTimer(minutes: Int) {
            val intent =
                Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_SET_SLEEP_TIMER)
                    .putExtra(PlaybackService.EXTRA_MINUTES, minutes)
            context.startService(intent)
        }

        fun cancelSleepTimer() {
            val intent =
                Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_CANCEL_SLEEP_TIMER)
            context.startService(intent)
        }

        private fun updateProgressPolling() {
            progressRunnable?.let { mainHandler.removeCallbacks(it) }
            progressRunnable = null
            if (!_isPlaying.value) return
            val r =
                object : Runnable {
                    override fun run() {
                        val c = controller ?: return
                        _positionMs.value = c.currentPosition.coerceAtLeast(0)
                        c.duration.takeIf { it > 0 }?.let { _durationMs.value = it }
                        mainHandler.postDelayed(this, 500L)
                    }
                }
            progressRunnable = r
            mainHandler.post(r)
        }

        private fun resolve(item: MediaItem): Track? =
            item.mediaId.toLongOrNull()?.let { repository.trackById(it) } ?: item.toSnapshotTrack()

        /** Mirrors the session's whole playlist, title and play order, then the current item. */
        private fun syncQueue(c: MediaController) {
            val count = c.mediaItemCount
            if (count == 0) {
                // Empty either because the service is still restoring (keep showing the
                // persisted snapshot) or because the queue was genuinely cleared.
                _queue.value = emptyList()
                _playOrder.value = emptyList()
                _queueIndex.value = -1
                _currentTrack.value = null
                return
            }
            _queue.value =
                (0 until count).map { i ->
                    val item = c.getMediaItemAt(i)
                    resolve(item) ?: Track(id = -1L - i, title = "", artist = "", album = "", durationMs = 0L, uri = "")
                }
            _playOrder.value = c.playOrder()
            _queueTitle.value = c.playlistMetadata.title?.toString().orEmpty()
            syncCurrentItem(c)
        }

        /**
         * Reconcile the displayed track, queue index, and timeline from the live
         * controller state. Reads directly from the controller rather than trusting a
         * listener callback's own parameters, since those can lag behind on an
         * automatic end-of-track advance.
         */
        private fun syncCurrentItem(c: MediaController) {
            val item = c.currentMediaItem ?: return
            val index = c.currentMediaItemIndex
            val resolved = _queue.value.getOrNull(index)?.takeIf { it.id.toString() == item.mediaId } ?: resolve(item)
            if (resolved != null) {
                _currentTrack.value = resolved
                _lastPlayedTrack.value = resolved
            }
            _queueIndex.value = index
            _positionMs.value = c.currentPosition.coerceAtLeast(0)
            _durationMs.value = c.duration.takeIf { it > 0 } ?: resolved?.durationMs ?: 0L
        }

        companion object {
            const val ACTION_SET_SLEEP_TIMER = "com.simplesound.app.SET_SLEEP_TIMER"
            const val ACTION_CANCEL_SLEEP_TIMER = "com.simplesound.app.CANCEL_SLEEP_TIMER"
            const val EXTRA_MINUTES = "minutes"
        }
    }
