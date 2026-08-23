package com.simplesound.app.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.simplesound.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var sleepTicker: Runnable? = null
    private var sleepEndElapsed: Long = 0L

    // ---- Crossfade (Samsung Music-style track-to-track overlap) ----
    // `player` stays the single source of truth for the MediaSession/notification/
    // queue for the whole app; crossfading a "shadow" ExoPlayer plays out the tail
    // of the outgoing track while `player` itself is jumped forward to the next
    // item early and ramped up from silence, so nothing about queue ownership or
    // session wiring changes. See maybeStartCrossfade() for the full walkthrough.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var crossfadeSeconds: Int = 0
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeRampRunnable: Runnable? = null
    private var crossfadeWatchRunnable: Runnable? = null
    // Set right before *we* programmatically skip `player` ahead to start a
    // crossfade, so the resulting onMediaItemTransition callback (fired for that
    // same skip) isn't mistaken for a user-initiated skip and doesn't cancel the
    // fade it just started.
    private var suppressNextTransitionCancel = false

    private val crossfadeListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startCrossfadeWatcher()
                return
            }
            stopCrossfadeWatcher()
            // isPlaying also dips false for a moment while a new item buffers --
            // notably right after our own seekToNextMediaItem() below, which would
            // otherwise cancel the fade it just started. playWhenReady tells the
            // two apart: only a genuine pause (user, sleep timer) clears it, so
            // the app lands cleanly rather than stuck partway through a fade the
            // user can't see progressing.
            if (!player.playWhenReady) cancelActiveCrossfade()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (suppressNextTransitionCancel) {
                // This is the transition *we* caused by seeking `player` ahead in
                // maybeStartCrossfade() -- the fade is already running, so leave it be.
                suppressNextTransitionCancel = false
                return
            }
            // Any other transition (user tapped next/previous, the track ended
            // naturally with crossfade off, a queue edit) means a fade already in
            // flight is now describing a track we've left -- drop it immediately
            // rather than let a stale shadow player keep talking underneath.
            cancelActiveCrossfade()
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelActiveCrossfade()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val remaining = sleepEndElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                _sleepTimerRemainingMs.value = 0L
                fireSleepTimer()
                return
            }
            _sleepTimerRemainingMs.value = remaining
            sleepHandler.postDelayed(this, 1_000L)
        }
    }

    private val sleepPrefs by lazy {
        getSharedPreferences(SLEEP_PREFS, android.content.Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(crossfadeListener)
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(TrackArtworkBitmapLoader(this))
            .build()
        restoreSleepTimerIfNeeded()

        val settingsStore = SettingsStore(applicationContext)
        serviceScope.launch {
            settingsStore.crossfadeSeconds.collect { seconds ->
                crossfadeSeconds = seconds
                if (seconds <= 0) cancelActiveCrossfade()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        _sleepTimerActive.value = true
        _sleepTimerRemainingMs.value = totalMs
        sleepEndElapsed = SystemClock.elapsedRealtime() + totalMs
        sleepPrefs.edit().putLong(KEY_DEADLINE_ELAPSED, sleepEndElapsed).apply()
        sleepTicker = ticker
        sleepHandler.post(ticker)
        val r = Runnable { fireSleepTimer() }
        sleepRunnable = r
        sleepHandler.postDelayed(r, totalMs)
    }

    private fun fireSleepTimer() {
        if (!_sleepTimerActive.value) return
        player.pause()
        _sleepTimerActive.value = false
        _sleepTimerRemainingMs.value = 0L
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepTicker?.let { sleepHandler.removeCallbacks(it) }
        sleepTicker = null
        sleepPrefs.edit().remove(KEY_DEADLINE_ELAPSED).apply()
    }

    fun cancelSleepTimer() = cancelSleepTimerInternal()

    private fun cancelSleepTimerInternal() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepTicker?.let { sleepHandler.removeCallbacks(it) }
        sleepTicker = null
        _sleepTimerActive.value = false
        _sleepTimerRemainingMs.value = 0L
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
        sleepEndElapsed = end
        _sleepTimerActive.value = true
        _sleepTimerRemainingMs.value = remaining
        sleepTicker = ticker
        sleepHandler.post(ticker)
        val r = Runnable { fireSleepTimer() }
        sleepRunnable = r
        sleepHandler.postDelayed(r, remaining)
    }

    // ---- Crossfade ----

    /** Polls playback position every 200ms (only while actually playing) to catch
     *  the moment the current track enters its last [crossfadeSeconds]. */
    private fun startCrossfadeWatcher() {
        if (crossfadeWatchRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                maybeStartCrossfade()
                crossfadeWatchRunnable = this
                sleepHandler.postDelayed(this, 200L)
            }
        }
        crossfadeWatchRunnable = r
        sleepHandler.post(r)
    }

    private fun stopCrossfadeWatcher() {
        crossfadeWatchRunnable?.let { sleepHandler.removeCallbacks(it) }
        crossfadeWatchRunnable = null
    }

    /**
     * Checks whether `player` has just entered the last [crossfadeSeconds] of the
     * current track and, if so, kicks off the overlap: a shadow [ExoPlayer] takes
     * over playing out the outgoing track from the current position while `player`
     * itself is jumped forward to the next item, muted, and ramped back up --
     * [rampCrossfade] fades the two against each other. `player` remains the one
     * true source for the MediaSession the whole time, so the notification/queue/
     * UI simply see it "skip ahead" a few seconds early rather than anything more
     * invasive.
     */
    private fun maybeStartCrossfade() {
        if (crossfadeSeconds <= 0) return
        if (crossfadePlayer != null) return // a fade is already running
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        if (!player.hasNextMediaItem()) return
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        // Never overlap more than half the track -- guards very short tracks
        // against a "crossfade" that's really the whole song playing twice.
        val effectiveMs = minOf(crossfadeSeconds * 1_000L, duration / 2)
        if (effectiveMs <= 0) return
        val remaining = duration - player.currentPosition
        if (remaining !in 0..effectiveMs) return

        val outgoingItem = player.currentMediaItem ?: return
        val outgoingPosition = player.currentPosition
        val startVolume = player.volume

        val shadow = buildCrossfadePlayer()
        shadow.setMediaItem(outgoingItem, outgoingPosition)
        shadow.volume = startVolume
        shadow.prepare()
        shadow.play()
        crossfadePlayer = shadow

        suppressNextTransitionCancel = true
        player.volume = 0f
        player.seekToNextMediaItem()

        rampCrossfade(effectiveMs, shadow, startVolume)
    }

    /** Linearly fades `player` in (0 -> [targetVolume]) while fading [shadow] out, in 50ms steps. */
    private fun rampCrossfade(durationMs: Long, shadow: ExoPlayer, targetVolume: Float) {
        val steps = (durationMs / 50L).coerceAtLeast(1)
        var step = 0
        val r = object : Runnable {
            override fun run() {
                if (crossfadePlayer !== shadow) return // superseded/cancelled
                step++
                val t = (step.toFloat() / steps).coerceIn(0f, 1f)
                player.volume = t * targetVolume
                shadow.volume = (1f - t) * targetVolume
                if (t >= 1f) {
                    finishCrossfade(shadow)
                } else {
                    crossfadeRampRunnable = this
                    sleepHandler.postDelayed(this, 50L)
                }
            }
        }
        crossfadeRampRunnable = r
        sleepHandler.postDelayed(r, 50L)
    }

    private fun finishCrossfade(shadow: ExoPlayer) {
        crossfadeRampRunnable?.let { sleepHandler.removeCallbacks(it) }
        crossfadeRampRunnable = null
        if (crossfadePlayer === shadow) crossfadePlayer = null
        shadow.release()
        player.volume = 1f
    }

    /** Stops and releases any fade in flight, snapping `player` back to full volume. */
    private fun cancelActiveCrossfade() {
        crossfadeRampRunnable?.let { sleepHandler.removeCallbacks(it) }
        crossfadeRampRunnable = null
        crossfadePlayer?.release()
        crossfadePlayer = null
        player.volume = 1f
    }

    /** Built fresh per fade and released when it ends -- crossfades are infrequent
     *  enough that pooling isn't worth the extra state. Doesn't manage audio focus
     *  of its own (`player` already holds it for both). */
    private fun buildCrossfadePlayer(): ExoPlayer =
        ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady) {
            player.stop()
            cancelSleepTimerInternal()
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopCrossfadeWatcher()
        cancelActiveCrossfade()
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

        const val ACTION_SET_SLEEP_TIMER = "com.simplesound.app.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.simplesound.app.CANCEL_SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"

        private val _sleepTimerActive = MutableStateFlow(false)
        val sleepTimerActive: StateFlow<Boolean> = _sleepTimerActive

        private val _sleepTimerRemainingMs = MutableStateFlow(0L)
        val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs
    }
}
