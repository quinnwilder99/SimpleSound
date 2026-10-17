package com.simplesound.app.playback.crossfade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Short-term loudness of one stretch of a track: one RMS level in dBFS per
 * [windowMs] window, the first starting at [startMs] (media time). Built by
 * [LoudnessScanner]; ends where the decoded audio ended, so for a tail scan
 * [endMs] is the real end of the track's audio.
 */
internal class LoudnessEnvelope(
    val startMs: Long,
    val windowMs: Int,
    val db: FloatArray,
) {
    val endMs: Long get() = startMs + db.size.toLong() * windowMs

    fun timeOf(index: Int): Long = startMs + index.toLong() * windowMs
}

/** How to get from the current track into the next one. */
internal sealed interface Transition {
    /**
     * Start the next track at [incomingStartMs] once the current one reaches
     * [fadeStartMs], overlapping the two for [fadeMs].
     */
    data class Crossfade(
        val fadeStartMs: Long,
        val fadeMs: Long,
        val incomingStartMs: Long,
    ) : Transition {
        val fadeEndMs: Long get() = fadeStartMs + fadeMs
    }

    /** The two tracks run straight into each other (a live album, a DJ mix, a
     *  concept album's segue): play them back to back untouched. */
    data object Gapless : Transition
}

/**
 * Picks the crossfade points from the outgoing track's tail and the incoming
 * track's head, the way radio automation does (Mixxx's intro/outro detection,
 * Liquidsoap's smart crossfade) rather than a fixed "N seconds before the file
 * ends":
 *
 * - The fade ends where the outgoing track *audibly* ends -- after its own
 *   fade-out has died away, before any trailing silence -- not at the end of the
 *   file. A song with 8 s of silence at the end otherwise "crossfades" into
 *   silence and the listener still hears a gap.
 * - The incoming track starts at its first sound, skipping leading silence.
 * - Two tracks of the same album where one runs straight into the next stay
 *   gapless; blending a segue just smears it.
 *
 * Pure so it is unit-tested without decoding anything.
 */
internal object CrossfadePlanner {
    /** Below this a window counts as silence (Mixxx uses -60; -55 keeps vinyl-rip
     *  hiss and dithered "silence" on the silent side). */
    const val SILENCE_DB = -55f

    /** The outgoing track is treated as over once its smoothed level has fallen this
     *  far below its body level -- the tail end of its own fade-out. Deliberately
     *  deep so a soft (but deliberate) outro isn't mistaken for a fade-out. */
    const val FADED_BELOW_BODY_DB = 30f

    /** Leading silence longer than this is assumed to be intentional (a hidden
     *  track, a dramatic pause) and kept. */
    const val MAX_INTRO_TRIM_MS = 10_000L

    /** Smoothing span for the fade-out detection, so a drum hit or a breath
     *  doesn't count as "still playing". */
    private const val SMOOTH_MS = 1_000

    /** A segue keeps playing at close to full level right up to the last moment. */
    private const val SEGUE_END_BELOW_BODY_DB = 15f
    private const val SEGUE_EDGE_MS = 1_000L
    private const val SEGUE_HEAD_MIN_DB = -35f
    private const val SEGUE_HEAD_MS = 500L

    /** Percentile of the smoothed tail used as the track's "body" (loud) level. */
    private const val BODY_PERCENTILE = 0.9

    /** The fixed-timing fallback: the last [fadeMs] of the track (at most half of it). */
    fun naive(
        durationMs: Long,
        fadeMs: Long,
    ): Transition.Crossfade {
        val len = minOf(fadeMs, durationMs / 2).coerceAtLeast(0)
        return Transition.Crossfade(durationMs - len, len, 0L)
    }

    fun plan(
        durationMs: Long,
        fadeMs: Long,
        tail: LoudnessEnvelope?,
        head: LoudnessEnvelope?,
        sameAlbum: Boolean,
    ): Transition {
        if (sameAlbum && isSegue(tail, head, durationMs)) return Transition.Gapless
        val incomingStart = head?.let(::introEnd) ?: 0L
        val end = (tail?.let(::audibleEnd) ?: durationMs).coerceAtMost(durationMs)
        // Never overlap more than half the track, same guard as the naive plan.
        val start = (end - fadeMs).coerceAtLeast(durationMs / 2)
        return if (end > start) {
            Transition.Crossfade(start, end - start, incomingStart)
        } else {
            naive(durationMs, fadeMs).copy(incomingStartMs = incomingStart)
        }
    }

    /** Where the outgoing track stops being audible. */
    fun audibleEnd(tail: LoudnessEnvelope): Long {
        val smoothed = smooth(tail)
        val body = bodyLevel(smoothed) ?: return tail.startMs // the whole tail is silent
        val threshold = maxOf(body - FADED_BELOW_BODY_DB, SILENCE_DB)
        val last = smoothed.indexOfLast { it > threshold }
        return if (last < 0) tail.startMs else tail.timeOf(last + 1)
    }

    /** Where the incoming track's first sound is (one window early, so its attack
     *  isn't clipped), or 0 when there's nothing worth trimming. */
    fun introEnd(head: LoudnessEnvelope): Long {
        val first = head.db.indexOfFirst { it > SILENCE_DB }
        if (first <= 0) return 0L
        val start = head.timeOf(first - 1).coerceAtLeast(0L)
        return if (start > MAX_INTRO_TRIM_MS) 0L else start
    }

    private fun isSegue(
        tail: LoudnessEnvelope?,
        head: LoudnessEnvelope?,
        durationMs: Long,
    ): Boolean {
        val coversEdges = tail != null && head != null && head.startMs == 0L && tail.endMs >= durationMs - SEGUE_EDGE_MS
        if (!coversEdges) return false
        val body = bodyLevel(smooth(tail!!))
        val tailEnd = meanDb(tail, tail.endMs - SEGUE_EDGE_MS, tail.endMs)
        val headStart = meanDb(head!!, 0L, SEGUE_HEAD_MS)
        return body != null &&
            tailEnd != null &&
            headStart != null &&
            tailEnd > body - SEGUE_END_BELOW_BODY_DB &&
            headStart > SEGUE_HEAD_MIN_DB
    }

    /** Centered moving average over [SMOOTH_MS], averaged as power, not as dB. */
    private fun smooth(env: LoudnessEnvelope): FloatArray {
        val half = (SMOOTH_MS / env.windowMs / 2).coerceAtLeast(0)
        val power = DoubleArray(env.db.size) { dbToPower(env.db[it]) }
        return FloatArray(env.db.size) { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(power.lastIndex)
            var sum = 0.0
            for (j in from..to) sum += power[j]
            powerToDb(sum / (to - from + 1))
        }
    }

    private fun bodyLevel(smoothed: FloatArray): Float? {
        val audible = smoothed.filter { it > SILENCE_DB }.sorted()
        if (audible.isEmpty()) return null
        return audible[((audible.size - 1) * BODY_PERCENTILE).toInt()]
    }

    private fun meanDb(
        env: LoudnessEnvelope,
        fromMs: Long,
        toMs: Long,
    ): Float? {
        var sum = 0.0
        var n = 0
        env.db.forEachIndexed { i, db ->
            val t = env.timeOf(i)
            if (t >= fromMs && t < toMs) {
                sum += dbToPower(db)
                n++
            }
        }
        return if (n == 0) null else powerToDb(sum / n)
    }

    private fun dbToPower(db: Float): Double = 10.0.pow(db / 10.0)

    private fun powerToDb(power: Double): Float =
        if (power <= 0.0) {
            MIN_DB
        } else {
            (10 * log10(power)).toFloat().coerceAtLeast(MIN_DB)
        }

    const val MIN_DB = -120f
}

/**
 * Equal-power gain curves. Two unrelated songs add up by power, not amplitude, so
 * a linear crossfade (both at 0.5 halfway = -6 dB each, -3 dB summed) audibly dips
 * in the middle; sin/cos keeps the summed power constant the whole way through.
 */
internal object CrossfadeCurves {
    fun incoming(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

    fun outgoing(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * HALF_PI).toFloat()

    private const val HALF_PI = PI / 2
}
