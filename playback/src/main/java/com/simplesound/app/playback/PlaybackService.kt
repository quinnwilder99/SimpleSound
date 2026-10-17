package com.simplesound.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.model.EdgeBarSide
import com.simplesound.app.data.model.Track
import com.simplesound.app.playback.crossfade.CrossfadeEngine
import com.simplesound.app.playback.eq.EqualizerAudioProcessor
import com.simplesound.app.playback.eq.EqualizerController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var settingsStore: SettingsStore

    @Inject lateinit var musicRepository: MusicRepository

    @Inject lateinit var equalizer: EqualizerController

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    // ---- Edge control bar (lock-screen control screen) ----
    private lateinit var lockScreenControl: LockScreenControlLauncher

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> lockScreenControl.screenOn = true
                    // ACTION_SCREEN_OFF and the unlock broadcast both mean "don't show
                    // it right now" -- screenOn=false covers the former; the latter is
                    // caught by refresh() re-checking isKeyguardLocked either way, but
                    // calling it explicitly here avoids relaunching it right after the
                    // user unlocks, in the gap before the next refresh trigger.
                    Intent.ACTION_SCREEN_OFF -> lockScreenControl.screenOn = false
                    Intent.ACTION_USER_PRESENT -> lockScreenControl.refresh()
                }
            }
        }

    // ---- Playback-error recovery ----
    // ExoPlayer does not auto-advance on error by default: a deleted file, a
    // corrupt track, or an invalid/blank URI leaves playback stuck in an error
    // state with no user-facing feedback and no way forward except a manual skip.
    // This mirrors ordinary skip behavior (seekToNextMediaItem) so a single bad
    // file doesn't stall the whole queue. errorSkipCount caps the retries at the
    // queue size so a queue that is entirely unplayable stops instead of spinning
    // through every item silently.
    private var errorSkipCount = 0

    private val errorRecoveryListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Only skip ahead when the user actually wants playback. A paused
                // session (e.g. the saved queue just restored on service start, or
                // media permission not granted yet) must not start playing -- or
                // walk the saved resume point -- on its own; pressing play re-prepares
                // and lands back here with playWhenReady set.
                if (!player.playWhenReady) return
                errorSkipCount++
                if (errorSkipCount > player.mediaItemCount) {
                    player.stop()
                    return
                }
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    player.stop()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errorSkipCount = 0
            }
        }

    private val lockScreenControlListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = lockScreenControl.refresh()

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) = lockScreenControl.refresh()

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = lockScreenControl.refresh()
        }

    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Crossfade ----
    // Two decks: at each fade the engine hands the session a pre-warmed player already
    // on the next track (swapPlayer below) while the old one plays out its tail. So
    // `player` is whichever deck is current, and everything session-level is attached
    // through playerListeners so it can move with it.
    private lateinit var crossfade: CrossfadeEngine

    private val playerAudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    private val crossfadeHost =
        object : CrossfadeEngine.Host {
            override val sessionPlayer: ExoPlayer get() = player

            override fun buildDeck(): ExoPlayer = buildPlayer(handleAudioFocus = false)

            override fun handOver(incoming: ExoPlayer) = swapPlayer(incoming)
        }

    /** Every listener that follows the session player across a crossfade swap. */
    private val playerListeners: List<Player.Listener> by lazy {
        listOf(
            crossfade.sessionListener,
            playRecordListener,
            errorRecoveryListener,
            stateSaveListener,
            lockScreenControlListener,
        )
    }

    // Mirrored locally so each settings collector below can pass LockScreenControlLauncher
    // both values together without waiting on the other flow to also emit.
    private var edgeBarEnabled: Boolean = true
    private var edgeBarSide: EdgeBarSide = EdgeBarSide.Default

    // ---- Play-stats recording ("Recently played" / "Most played") ----
    // A track only counts as "played" once it has actually been listened to for
    // min(PLAY_RECORD_THRESHOLD_MS, half its length) -- otherwise skimming/skipping
    // through the queue would spam Recently played and inflate Most played (the old
    // flat 5 s counted almost every skip). Listening time accumulates across pauses
    // within the same item. Recorded here (in the service, not PlayerController) so
    // it keeps working while the app is backgrounded.
    private var recordPlayRunnable: Runnable? = null
    private var recordedCurrentItem = false
    private var listenedMs = 0L
    private var listenStartElapsed = 0L

    private val playRecordListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    listenStartElapsed = SystemClock.elapsedRealtime()
                    scheduleRecordPlay()
                } else {
                    if (listenStartElapsed != 0L) listenedMs += SystemClock.elapsedRealtime() - listenStartElapsed
                    listenStartElapsed = 0L
                    cancelScheduledRecordPlay()
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                cancelScheduledRecordPlay()
                recordedCurrentItem = false
                listenedMs = 0L
                listenStartElapsed = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
                if (player.isPlaying) scheduleRecordPlay()
            }
        }

    private fun playRecordThresholdMs(): Long {
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return PLAY_RECORD_THRESHOLD_MS
        return minOf(PLAY_RECORD_THRESHOLD_MS, duration / 2)
    }

    private fun scheduleRecordPlay() {
        if (recordedCurrentItem) return
        cancelScheduledRecordPlay()
        val r = Runnable { recordCurrentItemPlayed() }
        recordPlayRunnable = r
        sleepHandler.postDelayed(r, (playRecordThresholdMs() - listenedMs).coerceAtLeast(0L))
    }

    private fun cancelScheduledRecordPlay() {
        recordPlayRunnable?.let { sleepHandler.removeCallbacks(it) }
        recordPlayRunnable = null
    }

    private fun recordCurrentItemPlayed() {
        recordPlayRunnable = null
        if (recordedCurrentItem) return
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        recordedCurrentItem = true
        musicRepository.recordTrackPlayed(trackId)
    }

    // ---- Playback state persistence (queue, position, shuffle/repeat) ----
    // Owned here rather than by the Activity: the service is what keeps playing
    // (and advancing through the queue) while the UI is gone, so it is the only
    // component that knows the real resume point if the process is killed later.
    // Saved on every structural change plus every SAVE_POSITION_INTERVAL_MS while
    // playing.
    private var hadQueue = false

    private val positionSaver =
        object : Runnable {
            override fun run() {
                saveState(includeQueue = false)
                sleepHandler.postDelayed(this, SAVE_POSITION_INTERVAL_MS)
            }
        }

    private val stateSaveListener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = saveState(includeQueue = true)

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveState(includeQueue = true)
            }

            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = saveState(includeQueue = true)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sleepHandler.removeCallbacks(positionSaver)
                if (isPlaying) sleepHandler.postDelayed(positionSaver, SAVE_POSITION_INTERVAL_MS)
                saveState(includeQueue = false)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) saveState(includeQueue = false)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
                musicRepository.saveShuffleEnabled(shuffleModeEnabled)

            override fun onRepeatModeChanged(repeatMode: Int) =
                musicRepository.saveRepeatMode(
                    repeatModeToSaved(repeatMode),
                )
        }

    private fun saveState(includeQueue: Boolean) {
        val count = player.mediaItemCount
        if (count == 0) {
            // Only a queue that existed this session and was then emptied is a real
            // clear; an empty player at startup just means the restore hasn't landed.
            if (hadQueue) {
                musicRepository.saveQueue(null, emptyList(), -1)
                musicRepository.saveLastPlayedTrack(null)
                hadQueue = false
            }
            return
        }
        hadQueue = true
        if (includeQueue) {
            val ids = (0 until count).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
            musicRepository.saveQueue(player.playlistMetadata.title?.toString(), ids, player.currentMediaItemIndex)
        }
        currentTrackSnapshot()?.let { musicRepository.saveLastPlayedTrack(it, player.currentPosition) }
    }

    private fun currentTrackSnapshot(): Track? {
        val item = player.currentMediaItem ?: return null
        return item.mediaId.toLongOrNull()?.let { musicRepository.trackById(it) } ?: item.toSnapshotTrack()
    }

    /**
     * Rebuilds the last saved queue from the library. Falls back to just the last
     * played track when the saved queue no longer resolves (tracks deleted since).
     */
    private suspend fun loadSavedQueue(): MediaItemsWithStartPosition? {
        musicRepository.awaitLibraryLoaded()
        val lastId = musicRepository.lastPlayedTrackId()
        val tracks =
            musicRepository.tracksByIds(musicRepository.lastQueueTrackIds())
                .ifEmpty { listOfNotNull(musicRepository.trackById(lastId)) }
        if (tracks.isEmpty()) return null
        val byId = tracks.indexOfFirst { it.id == lastId }
        val index = if (byId >= 0) byId else musicRepository.lastQueueIndex().coerceIn(0, tracks.lastIndex)
        val position = if (byId >= 0) musicRepository.lastPlayedPosition() else 0L
        return MediaItemsWithStartPosition(tracks.map { it.toMediaItem() }, index, position)
    }

    /** Only called right before the saved queue is actually handed to the player. */
    private fun applySavedQueueTitle() {
        player.playlistMetadata = MediaMetadata.Builder().setTitle(musicRepository.lastQueueTitle()).build()
    }

    /** Set once a saved queue has been handed to the player (by either path below). */
    private var restoreDone = false

    /**
     * Loads the saved queue into an empty player, paused, whenever the service
     * starts -- so the app, the notification and headset buttons all see the same
     * queue. playWhenReady is left alone: if a controller already pressed play while
     * the library was loading, playback starts as soon as the items land.
     */
    private fun restoreSavedQueueIfEmpty() {
        serviceScope.launch {
            val saved = loadSavedQueue() ?: return@launch
            if (restoreDone || player.mediaItemCount > 0) return@launch
            restoreDone = true
            applySavedQueueTitle()
            player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
            player.prepare()
        }
    }

    /**
     * Media3 playback resumption: a headset/Bluetooth play button or the system media
     * controls pressing play while the app isn't running restarts this service with
     * an empty player and asks what to play (needs MediaButtonReceiver in the manifest).
     */
    private val sessionCallback =
        object : MediaSession.Callback {
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaItemsWithStartPosition>()
                serviceScope.launch {
                    val saved = loadSavedQueue()
                    if (saved != null) {
                        restoreDone = true
                        applySavedQueueTitle()
                        future.set(saved)
                    } else {
                        future.setException(UnsupportedOperationException("Nothing to resume"))
                    }
                }
                return future
            }
        }

    private val sleepPrefs by lazy {
        getSharedPreferences(SLEEP_PREFS, android.content.Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer(handleAudioFocus = true)
        player.shuffleModeEnabled = musicRepository.lastShuffleEnabled()
        player.repeatMode = savedToRepeatMode(musicRepository.lastRepeatMode())
        crossfade = CrossfadeEngine(this, serviceScope, playerAudioAttributes, crossfadeHost)
        lockScreenControl = LockScreenControlLauncher(this) { player }
        playerListeners.forEach(player::addListener)
        mediaSession =
            MediaSession.Builder(this, player)
                .setBitmapLoader(TrackArtworkBitmapLoader(this))
                .setCallback(sessionCallback)
                .build()
        restoreSleepTimerIfNeeded()
        restoreSavedQueueIfEmpty()

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        serviceScope.launch {
            combine(settingsStore.crossfadeSeconds, settingsStore.crossfadeSmart, ::Pair).collect { (seconds, smart) ->
                crossfade.setSettings(seconds, smart)
            }
        }
        serviceScope.launch {
            settingsStore.edgeBarEnabled.collect { enabled ->
                edgeBarEnabled = enabled
                lockScreenControl.setSettings(enabled, edgeBarSide)
            }
        }
        serviceScope.launch {
            settingsStore.edgeBarSide.collect { side ->
                edgeBarSide = side
                lockScreenControl.setSettings(edgeBarEnabled, side)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                setSleepTimer(minutes)
            }
            ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimerInternal()
        if (minutes <= 0) return
        val totalMs = minutes * 60_000L
        val end = SystemClock.elapsedRealtime() + totalMs
        _sleepTimerEndElapsed.value = end
        _sleepTimerActive.value = true
        sleepPrefs.edit().putLong(KEY_DEADLINE_ELAPSED, end).apply()
        val r = Runnable { fireSleepTimer() }
        sleepRunnable = r
        sleepHandler.postDelayed(r, totalMs)
    }

    private fun fireSleepTimer() {
        if (!_sleepTimerActive.value) return
        player.pause()
        _sleepTimerActive.value = false
        _sleepTimerEndElapsed.value = 0L
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepPrefs.edit().remove(KEY_DEADLINE_ELAPSED).apply()
    }

    fun cancelSleepTimer() = cancelSleepTimerInternal()

    private fun cancelSleepTimerInternal() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        _sleepTimerActive.value = false
        _sleepTimerEndElapsed.value = 0L
        sleepPrefs.edit().remove(KEY_DEADLINE_ELAPSED).apply()
    }

    private fun restoreSleepTimerIfNeeded() {
        val end = sleepPrefs.getLong(KEY_DEADLINE_ELAPSED, 0L)
        if (end <= 0L) return
        val remaining = end - SystemClock.elapsedRealtime()
        if (remaining <= 1_000L) {
            sleepPrefs.edit().remove(KEY_DEADLINE_ELAPSED).apply()
            return
        }
        _sleepTimerEndElapsed.value = end
        _sleepTimerActive.value = true
        val r = Runnable { fireSleepTimer() }
        sleepRunnable = r
        sleepHandler.postDelayed(r, remaining)
    }

    // ---- Players ----

    /**
     * The session player and every crossfade deck come from here, so they share the EQ,
     * wake mode and noisy-output handling. Only the session player holds audio focus;
     * a deck gets it when the crossfade engine hands it over.
     */
    private fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer =
        ExoPlayer.Builder(this, equalizerRenderersFactory())
            .setAudioAttributes(playerAudioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake while playing so the player (and the crossfade
            // timer) keep running with the screen off. Audio offload is deliberately
            // not used: on the Pixel it left playback stuck "playing" in silence
            // ~20 min into a screen-off session, with Play unable to recover it.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

    /**
     * Crossfade hand-over: [incoming] (already on the next track) becomes the session
     * player. The session-level listeners never saw it change track -- that happened
     * on a deck they weren't attached to -- so they're told about the transition here.
     */
    private fun swapPlayer(incoming: ExoPlayer) {
        val outgoing = player
        playerListeners.forEach(outgoing::removeListener)
        player = incoming
        playerListeners.forEach(incoming::addListener)
        mediaSession?.player = incoming
        playerListeners.forEach {
            it.onMediaItemTransition(incoming.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            it.onIsPlayingChanged(incoming.isPlaying)
        }
    }

    /**
     * Renderers whose audio sink runs an [EqualizerAudioProcessor] reading the live
     * [EqualizerController] settings. Each player gets its own processor (filter
     * memory is per stream); with the EQ off it passes audio straight through.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun equalizerRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(EqualizerAudioProcessor { equalizer.settings.value }))
                    .build()
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState(includeQueue = true)
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady) {
            player.stop()
            cancelSleepTimerInternal()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState(includeQueue = true)
        sleepHandler.removeCallbacks(positionSaver)
        crossfade.release()
        cancelScheduledRecordPlay()
        // Unlike onTaskRemoved, onDestroy can run without onTaskRemoved having run
        // first (e.g. system-initiated teardown while a sleep timer is armed). Without
        // this, a pending sleepRunnable would fire later on sleepHandler
        // (unscoped to the service) and call fireSleepTimer() -> player.pause() on the
        // ExoPlayer instance being released below.
        cancelSleepTimerInternal()
        runCatching { unregisterReceiver(screenStateReceiver) }
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val SLEEP_PREFS = "simplesound_sleep_timer"
        private const val KEY_DEADLINE_ELAPSED = "deadline_elapsed"

        /** Listening time before a track counts as "played" (or half the track, if shorter). */
        private const val PLAY_RECORD_THRESHOLD_MS = 30_000L

        /** Pausing, seeking and track changes already save the exact position; this
         *  periodic save only bounds what a process kill mid-playback can lose, so it is
         *  kept infrequent. */
        private const val SAVE_POSITION_INTERVAL_MS = 15_000L

        /** Saved repeat encoding (0 = off, 1 = all, 2 = one) <-> [Player] constants. */
        private fun repeatModeToSaved(mode: Int): Int =
            when (mode) {
                Player.REPEAT_MODE_ALL -> 1
                Player.REPEAT_MODE_ONE -> 2
                else -> 0
            }

        private fun savedToRepeatMode(saved: Int): Int =
            when (saved) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }

        const val ACTION_SET_SLEEP_TIMER = "com.simplesound.app.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.simplesound.app.CANCEL_SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"

        /** Matched by an intent-filter on LockScreenControlActivity (:app) -- see
         *  [LockScreenControlLauncher] for why this is a broadcast-style action rather
         *  than a direct class reference. */
        const val ACTION_SHOW_LOCK_CONTROL = "com.simplesound.app.SHOW_LOCK_CONTROL"

        private val _sleepTimerActive = MutableStateFlow(false)
        val sleepTimerActive: StateFlow<Boolean> = _sleepTimerActive

        /** [SystemClock.elapsedRealtime] deadline of the active sleep timer, 0 when none.
         *  A deadline rather than a ticking "remaining" value so the service never wakes
         *  up once a second just to update a number; whoever displays the countdown
         *  (the Sleep timer screen) ticks it itself, and only while visible. */
        private val _sleepTimerEndElapsed = MutableStateFlow(0L)
        val sleepTimerEndElapsed: StateFlow<Long> = _sleepTimerEndElapsed
    }
}
