package com.simplesound.app.playback.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrossfadePlannerTest {
    private val window = 50

    /** Envelope from [startMs] built out of (durationMs to dB) segments. */
    private fun env(
        startMs: Long,
        vararg segments: Pair<Long, Float>,
    ): LoudnessEnvelope {
        val db = segments.flatMap { (ms, level) -> List((ms / window).toInt()) { level } }
        return LoudnessEnvelope(startMs, window, db.toFloatArray())
    }

    private val duration = 200_000L
    private val loudHead = env(0L, 12_000L to -14f)

    @Test
    fun `no analysis falls back to the last N seconds`() {
        val plan = CrossfadePlanner.plan(duration, 6_000L, null, null, sameAlbum = false)
        assertEquals(Transition.Crossfade(194_000L, 6_000L, 0L), plan)
    }

    @Test
    fun `naive plan never overlaps more than half a short track`() {
        assertEquals(Transition.Crossfade(5_000L, 5_000L, 0L), CrossfadePlanner.naive(10_000L, 12_000L))
    }

    @Test
    fun `trailing silence is skipped - the fade ends where the music does`() {
        // Loud until 190 s, then 10 s of digital silence.
        val tail = env(155_000L, 35_000L to -12f, 10_000L to -90f)
        val plan = CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = false) as Transition.Crossfade
        assertTrue("fade should end near 190 s, was ${plan.fadeEndMs}", abs(plan.fadeEndMs - 190_000L) <= 600L)
        assertEquals(6_000L, plan.fadeMs)
    }

    @Test
    fun `a natural fade-out is crossfaded once it is effectively over, not at the file end`() {
        // Body at -12 dB, a 15 s fade-out stepping down to -60, then near-silence.
        val tail =
            env(
                155_000L,
                25_000L to -12f,
                5_000L to -25f,
                5_000L to -38f,
                5_000L to -48f,
                10_000L to -70f,
            )
        val plan = CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = false) as Transition.Crossfade
        // -38 dB is 26 dB under the body (still audible), -48 is 36 under (faded out).
        assertTrue("fade end ${plan.fadeEndMs}", plan.fadeEndMs in 189_000L..191_000L)
    }

    @Test
    fun `a soft outro is not mistaken for a fade-out`() {
        // Drops 20 dB for a quiet piano coda that runs to the very end.
        val tail = env(155_000L, 30_000L to -12f, 15_000L to -32f)
        val plan = CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = false) as Transition.Crossfade
        assertEquals(194_000L, plan.fadeStartMs)
    }

    @Test
    fun `leading silence of the next track is skipped`() {
        val head = env(0L, 2_000L to -95f, 10_000L to -14f)
        val plan = CrossfadePlanner.plan(duration, 6_000L, null, head, sameAlbum = false) as Transition.Crossfade
        assertEquals(1_950L, plan.incomingStartMs) // one window before the first sound
    }

    @Test
    fun `very long leading silence is kept as intentional`() {
        val head = env(0L, 12_000L to -95f)
        assertEquals(0L, CrossfadePlanner.introEnd(head))
    }

    @Test
    fun `same-album segue stays gapless`() {
        val tail = env(155_000L, 45_000L to -12f)
        assertEquals(Transition.Gapless, CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = true))
    }

    @Test
    fun `the same continuous edges on different albums still crossfade`() {
        val tail = env(155_000L, 45_000L to -12f)
        assertTrue(CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = false) is Transition.Crossfade)
    }

    @Test
    fun `same album but with a gap between songs crossfades`() {
        val tail = env(155_000L, 40_000L to -12f, 5_000L to -90f)
        assertTrue(CrossfadePlanner.plan(duration, 6_000L, tail, loudHead, sameAlbum = true) is Transition.Crossfade)
    }

    @Test
    fun `equal-power curves keep summed power constant`() {
        for (i in 0..20) {
            val p = i / 20f
            val power = CrossfadeCurves.incoming(p).let { it * it } + CrossfadeCurves.outgoing(p).let { it * it }
            assertEquals(1f, power, 1e-5f)
        }
        assertEquals(0f, CrossfadeCurves.incoming(0f), 1e-6f)
        assertEquals(1f, CrossfadeCurves.incoming(1f), 1e-6f)
        assertEquals(0.7071f, CrossfadeCurves.outgoing(0.5f), 1e-4f) // -3 dB, not linear's -6
    }
}
