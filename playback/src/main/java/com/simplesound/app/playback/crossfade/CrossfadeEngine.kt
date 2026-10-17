package com.simplesound.app.playback.crossfade

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.simplesound.app.playback.playOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Two-deck crossfade, the way DJ software (and SimpMusic's Media3 adapter) does it:
 *
 * 1. About a minute before the end of a track, the tail of the current track and the
 *    head of the next are decoded off-thread into loudness envelopes and
 *    [CrossfadePlanner] picks the fade points (skipping trailing/leading silence,
 *    leaving album segues gapless).
 * 2. A few seconds before the fade, a second "standby" [ExoPlayer] is built with a
 *    copy of the queue, positioned at the next track's first sound, prepared and
 *    left paused at zero volume, so it starts instantly.
 * 3. At the fade point the standby starts and [Host.handOver] makes it *the* session
 *    player (MediaSession.setPlayer). The old player is trimmed to just its current
 *    item and keeps playing its tail, untouched, while the two are faded against
 *    each other on equal-power curves; then it is released.
 *
 * The outgoing audio is never stopped, re-opened or seeked, which is what made the
 * old single-shadow-player version stutter at the start of every fade. Fade
 * progress follows the incoming track's own playback position, so buffering,
 * pausing and speed changes can't desync it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CrossfadeEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioAttributes: AudioAttributes,
    private val host: Host,
) {
    interface Host {
        val sessionPlayer: ExoPlayer

        /** A new deck: same renderers/EQ/wake mode as the session player, not yet
         *  holding audio focus. */
        fun buildDeck(): ExoPlayer

        /** Makes [incoming] the session player, moving every session-level
         *  listener (including [CrossfadeEngine.sessionListener]) over to it. */
        fun handOver(incoming: ExoPlayer)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var fadeMs = 0L
    private var smart = true

    /** Which transition the state below describes; anything else invalidates it. */
    private data class Target(
        val outgoingIndex: Int,
        val outgoingId: String,
        val incomingIndex: Int,
        val incomingId: String,
        val fadeMs: Long,
        val smart: Boolean,
    )

    private var target: Target? = null
    private var plan: Transition? = null
    private var planJob: Job? = null
    private var standby: ExoPlayer? = null
    private var standbyFailed = false

    private class Fade(
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val incomingStartMs: Long,
        val lengthMs: Long,
    )

    private var fade: Fade? = null
    private var handingOver = false

    /** Envelopes by "tail:id" / "head:id" -- repeat-all, going back a track or a
     *  queue rebuild re-plan the same edges without decoding them again. */
    private val envelopes = LruCache<String, LoudnessEnvelope>(ENVELOPE_CACHE_SIZE)

    private val watchRunnable = Runnable { onWatch() }
    private val tickRunnable = Runnable { onTick() }

    fun setSettings(
        seconds: Int,
        smart: Boolean,
    ) {
        fadeMs = seconds * MS_PER_S
        this.smart = smart
        if (seconds <= 0) {
            resetTarget()
            finishFade()
        }
        rearm()
    }

    fun release() {
        handler.removeCallbacks(watchRunnable)
        resetTarget()
        finishFade()
    }

    /** Attached by the host to whichever deck is the session player. */
    val sessionListener: Player.Listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = rearm()

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) = mirrorPlayState()

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = mirrorPlayState()

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek on the incoming track mid-fade: the overlap no longer lines up.
                val seek =
                    reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                if (seek) finishFade()
                rearm()
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                if (handingOver) return
                // Skipped, or advanced on its own (a gapless plan, crossfade not ready):
                // whatever was planned or fading is about tracks we've left.
                finishFade()
                resetTarget()
                rearm()
            }

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                // A queue edit can change what "next" is; the standby holds a copy of
                // the old queue. (SOURCE_UPDATE -- a duration becoming known -- only
                // moves the timing.)
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) resetTarget()
                rearm()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                resetTarget()
                rearm()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                resetTarget()
                rearm()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                standby?.playbackParameters = playbackParameters
                fade?.outgoing?.playbackParameters = playbackParameters
                rearm()
            }

            override fun onPlayerError(error: PlaybackException) {
                finishFade()
                resetTarget()
            }
        }

    // ---- Scheduling ----

    /** Sleeps until the next stage (analyse / prepare / start) is due. No polling:
     *  a long sleep to just before the moment, then one exact wake-up. */
    private fun rearm() {
        handler.removeCallbacks(watchRunnable)
        val p = host.sessionPlayer
        if (fadeMs <= 0 || !p.isPlaying) return
        val delay = nextStageDelayMs(p) ?: return
        handler.postDelayed(watchRunnable, delay)
    }

    private fun nextStageDelayMs(p: ExoPlayer): Long? {
        val nextAt = nextStageAtMs(p.duration) ?: return null
        val speed = p.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        val delay = ((nextAt - p.currentPosition) / speed).toLong()
        // Position estimates drift a little over a long sleep; land short and re-aim.
        return if (delay > COARSE_SLEEP_MS) delay - REAIM_MS else delay.coerceAtLeast(MIN_DELAY_MS)
    }

    /** Track position of the next stage, or null when there's nothing left to do for
     *  this track (no known duration, a gapless plan, a failed standby). */
    private fun nextStageAtMs(duration: Long): Long? {
        if (duration <= 0 || duration == C.TIME_UNSET) return null
        return when (val planned = effectivePlan(duration)) {
            null -> duration - ANALYZE_LEAD_MS
            Transition.Gapless -> null
            is Transition.Crossfade ->
                when {
                    standbyFailed -> null // plain transition this time
                    standby == null -> planned.fadeStartMs - PREPARE_LEAD_MS
                    else -> planned.fadeStartMs
                }
        }
    }

    /** The plan for the current target: the analysed one, else (while analysis is
     *  still running) the fixed-timing fallback, else null -- not analysed yet. */
    private fun effectivePlan(duration: Long): Transition? =
        plan ?: if (planJob != null) CrossfadePlanner.naive(duration, fadeMs) else null

    private fun onWatch() {
        val p = host.sessionPlayer
        when {
            !isEligible(p) -> resetTarget()
            // Only possible on a track shorter than two fades; let the current one finish.
            fade != null -> handler.postDelayed(watchRunnable, BUSY_RETRY_MS)
            else -> if (!advanceStages(p)) rearm()
        }
    }

    /** Runs whichever stages are due; true once the fade has started. */
    private fun advanceStages(p: ExoPlayer): Boolean {
        ensureTarget(p)
        val duration = p.duration
        val pos = p.currentPosition
        if (plan == null && planJob == null && pos >= duration - ANALYZE_LEAD_MS) startPlanning(p, duration)
        val planned = effectivePlan(duration) as? Transition.Crossfade ?: return false
        if (standby == null && !standbyFailed && pos >= planned.fadeStartMs - PREPARE_LEAD_MS) {
            standby = buildStandby(p, planned.incomingStartMs)
        }
        val due = pos >= planned.fadeStartMs && standby != null
        if (due) startFade(p, planned, pos)
        return due
    }

    private fun isEligible(p: ExoPlayer): Boolean =
        fadeMs > 0 &&
            p.repeatMode != Player.REPEAT_MODE_ONE &&
            p.hasNextMediaItem() &&
            p.duration > 0 &&
            p.duration != C.TIME_UNSET

    private fun ensureTarget(p: ExoPlayer) {
        val next = p.nextMediaItemIndex
        val key =
            Target(
                outgoingIndex = p.currentMediaItemIndex,
                outgoingId = p.currentMediaItem?.mediaId.orEmpty(),
                incomingIndex = next,
                incomingId = p.getMediaItemAt(next).mediaId,
                fadeMs = fadeMs,
                smart = smart,
            )
        if (key == target) return
        resetTarget()
        target = key
    }

    private fun resetTarget() {
        planJob?.cancel()
        planJob = null
        plan = null
        target = null
        standbyFailed = false
        standby?.release()
        standby = null
    }

    // ---- Analysis ----

    private fun startPlanning(
        p: ExoPlayer,
        duration: Long,
    ) {
        val outgoing = p.currentMediaItem ?: return
        val incoming = p.getMediaItemAt(p.nextMediaItemIndex)
        val fade = fadeMs
        if (!smart) {
            plan = CrossfadePlanner.naive(duration, fade)
            return
        }
        val forTarget = target
        val job =
            scope.launch {
                val tail =
                    envelope(
                        outgoing,
                        TAIL_KEY,
                        (duration - TAIL_SCAN_MS).coerceAtLeast(duration / 2),
                        duration + SCAN_SLACK_MS,
                    )
                val head = envelope(incoming, HEAD_KEY, 0L, HEAD_SCAN_MS)
                if (target !== forTarget) return@launch
                planJob = null
                onPlanReady(CrossfadePlanner.plan(duration, fade, tail, head, sameAlbum(outgoing, incoming)))
            }
        // The scope dispatches immediately, so with both envelopes cached the job has
        // already finished (and set the plan) by the time launch returns.
        if (!job.isCompleted) planJob = job
    }

    private fun onPlanReady(result: Transition) {
        plan = result
        val deck = standby
        if (deck != null) {
            when (result) {
                // Built on the fallback plan while analysis was still running.
                Transition.Gapless -> {
                    deck.release()
                    standby = null
                }
                is Transition.Crossfade ->
                    if (deck.currentPosition != result.incomingStartMs) deck.seekTo(result.incomingStartMs)
            }
        }
        rearm()
    }

    private suspend fun envelope(
        item: MediaItem,
        kind: String,
        fromMs: Long,
        toMs: Long,
    ): LoudnessEnvelope? {
        val key = "$kind:${item.mediaId}"
        val uri = item.localConfiguration?.uri
        return envelopes.get(key) ?: uri?.let {
            withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                LoudnessScanner.scan(context, it, fromMs, toMs) { ctx.isActive }
            }?.also { env -> envelopes.put(key, env) }
        }
    }

    private fun sameAlbum(
        a: MediaItem,
        b: MediaItem,
    ): Boolean {
        val albumA = a.mediaMetadata.albumTitle?.toString().orEmpty()
        val albumB = b.mediaMetadata.albumTitle?.toString().orEmpty()
        return albumA.isNotBlank() && albumA != UNKNOWN_ALBUM && albumA == albumB
    }

    // ---- Decks ----

    /** A paused, silent copy of [p]'s queue sitting on the next track. */
    private fun buildStandby(
        p: ExoPlayer,
        startMs: Long,
    ): ExoPlayer {
        val deck = host.buildDeck()
        deck.volume = 0f
        deck.setMediaItems(List(p.mediaItemCount) { p.getMediaItemAt(it) }, p.nextMediaItemIndex, startMs)
        if (p.shuffleModeEnabled) {
            // Keep the exact shuffled order going forward, not a fresh reshuffle.
            deck.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(p.playOrder().toIntArray(), System.nanoTime()))
        }
        deck.shuffleModeEnabled = p.shuffleModeEnabled
        deck.repeatMode = p.repeatMode
        deck.playlistMetadata = p.playlistMetadata
        deck.playbackParameters = p.playbackParameters
        deck.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // A bad next file: drop the crossfade so the session player reaches
                    // it normally and its own error recovery skips it.
                    if (standby === deck) {
                        deck.release()
                        standby = null
                        standbyFailed = true
                    }
                }
            },
        )
        deck.prepare()
        return deck
    }

    private fun startFade(
        outgoing: ExoPlayer,
        planned: Transition.Crossfade,
        pos: Long,
    ) {
        val incoming = standby ?: return
        standby = null
        handler.removeCallbacks(watchRunnable)
        // Joined late (a seek into the fade, a slow wake-up): fade over what's left,
        // but at least briefly, so a seek past the audible end still blends rather
        // than sitting through the trailing silence.
        val length = (planned.fadeEndMs - pos).coerceIn(MIN_FADE_MS, planned.fadeMs.coerceAtLeast(MIN_FADE_MS))

        // Hand audio focus over first: if the incoming deck requested focus while the
        // outgoing one still held it, the outgoing one would get a focus loss and pause.
        outgoing.setAudioAttributes(audioAttributes, false)
        incoming.setAudioAttributes(audioAttributes, true)
        incoming.playWhenReady = outgoing.playWhenReady
        handingOver = true
        try {
            host.handOver(incoming)
        } finally {
            handingOver = false
        }

        // The old deck now only plays out its own tail.
        val i = outgoing.currentMediaItemIndex
        if (i + 1 < outgoing.mediaItemCount) outgoing.removeMediaItems(i + 1, outgoing.mediaItemCount)
        if (i > 0) outgoing.removeMediaItems(0, i)
        outgoing.repeatMode = Player.REPEAT_MODE_OFF
        outgoing.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && fade?.outgoing === outgoing) finishFade()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (fade?.outgoing === outgoing) finishFade()
                }
            },
        )

        target = null
        plan = null
        fade = Fade(outgoing, incoming, incoming.currentPosition, length)
        handler.post(tickRunnable)
        rearm()
    }

    /** Pausing (user, sleep timer, a call, headphones unplugged) pauses both decks;
     *  resuming picks the fade up where it left off. */
    private fun mirrorPlayState() {
        val f = fade ?: return
        val playing =
            f.incoming.playWhenReady &&
                f.incoming.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        f.outgoing.playWhenReady = playing
        handler.removeCallbacks(tickRunnable)
        if (playing) handler.post(tickRunnable)
    }

    private fun onTick() {
        val f = fade ?: return
        // Driven by the incoming track's own clock, not wall time: until it actually
        // starts producing audio, the outgoing one stays at full volume.
        val elapsed = (f.incoming.currentPosition - f.incomingStartMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / f.lengthMs).coerceIn(0f, 1f)
        f.incoming.volume = CrossfadeCurves.incoming(progress)
        f.outgoing.volume = CrossfadeCurves.outgoing(progress)
        if (progress >= 1f) {
            finishFade()
        } else {
            handler.postDelayed(tickRunnable, TICK_MS)
        }
    }

    /** Ends any fade in flight: the outgoing deck goes, the incoming one is at full volume. */
    private fun finishFade() {
        handler.removeCallbacks(tickRunnable)
        val f = fade ?: return
        fade = null
        f.outgoing.release()
        f.incoming.volume = 1f
    }

    private companion object {
        const val MS_PER_S = 1_000L

        /** When to analyse: the tail scan covers the last [TAIL_SCAN_MS] and the fade
         *  can't start before it, so this leaves ample time for the decode. */
        const val ANALYZE_LEAD_MS = 75_000L
        const val TAIL_SCAN_MS = 45_000L
        const val HEAD_SCAN_MS = 12_000L
        const val SCAN_SLACK_MS = 2_000L

        /** How long before the fade the standby deck is built and prepared. */
        const val PREPARE_LEAD_MS = 5_000L

        const val COARSE_SLEEP_MS = 1_500L
        const val REAIM_MS = 1_000L
        const val MIN_DELAY_MS = 10L
        const val BUSY_RETRY_MS = 250L

        const val MIN_FADE_MS = 500L

        /** Volume update period during a fade (~33 Hz; AudioFlinger smooths each step). */
        const val TICK_MS = 30L

        const val ENVELOPE_CACHE_SIZE = 64
        const val TAIL_KEY = "tail"
        const val HEAD_KEY = "head"

        /** What Track.albumOrUnknown puts in the metadata for a blank album. */
        const val UNKNOWN_ALBUM = "<unknown>"
    }
}
