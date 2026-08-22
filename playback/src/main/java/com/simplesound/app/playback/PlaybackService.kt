package com.simplesound.app.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: Player

    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var sleepTicker: Runnable? = null
    private var sleepEndElapsed: Long = 0L

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
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(TrackArtworkBitmapLoader(this))
            .build()
        restoreSleepTimerIfNeeded()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady) {
            player.stop()
            cancelSleepTimerInternal()
            stopSelf()
        }
    }

    override fun onDestroy() {
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
