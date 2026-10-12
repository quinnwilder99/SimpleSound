package com.simplesound.app.playback.eq

import com.simplesound.app.data.model.EqFilter
import com.simplesound.app.data.model.EqFilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalized biquad coefficients (a0 = 1) for one [EqFilter], from the RBJ Audio EQ
 * Cookbook -- the same formulas AutoEq uses to design its ParametricEQ.txt filters,
 * so its corrections come out exactly as measured. Returns null when the filter can't
 * be realized at [sampleRate] (corner at or above Nyquist), so it's skipped instead
 * of going unstable.
 */
internal fun biquadCoefficients(
    filter: EqFilter,
    sampleRate: Int,
): BiquadCoefficients? {
    if (filter.frequencyHz <= 0.0 || filter.q <= 0.0 || filter.frequencyHz >= sampleRate * MAX_FC_RATIO) return null
    val a = 10.0.pow(filter.gainDb / 40.0)
    val w0 = 2.0 * PI * filter.frequencyHz / sampleRate
    val cosW = cos(w0)
    val alpha = sin(w0) / (2.0 * filter.q)
    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
    val (b, aCoef) =
        when (filter.type) {
            EqFilterType.PK ->
                doubleArrayOf(1 + alpha * a, -2 * cosW, 1 - alpha * a) to
                    doubleArrayOf(1 + alpha / a, -2 * cosW, 1 - alpha / a)
            EqFilterType.LSC ->
                doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha),
                    2 * a * ((a - 1) - (a + 1) * cosW),
                    a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha),
                ) to
                    doubleArrayOf(
                        (a + 1) + (a - 1) * cosW + twoSqrtAAlpha,
                        -2 * ((a - 1) + (a + 1) * cosW),
                        (a + 1) + (a - 1) * cosW - twoSqrtAAlpha,
                    )
            EqFilterType.HSC ->
                doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha),
                    -2 * a * ((a - 1) + (a + 1) * cosW),
                    a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha),
                ) to
                    doubleArrayOf(
                        (a + 1) - (a - 1) * cosW + twoSqrtAAlpha,
                        2 * ((a - 1) - (a + 1) * cosW),
                        (a + 1) - (a - 1) * cosW - twoSqrtAAlpha,
                    )
        }
    val a0 = aCoef[0]
    return BiquadCoefficients(b[0] / a0, b[1] / a0, b[2] / a0, aCoef[1] / a0, aCoef[2] / a0)
}

internal data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Squared magnitude of this filter's response at [frequencyHz]. */
    fun powerAt(
        frequencyHz: Double,
        sampleRate: Int,
    ): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val c2 = cos(2 * w)
        val s1 = sin(w)
        val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = b1 * s1 + b2 * s2
        val denRe = 1 + a1 * c1 + a2 * c2
        val denIm = a1 * s1 + a2 * s2
        return (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
    }
}

/**
 * Highest gain, in dB, the whole cascade applies anywhere in the audible band.
 * Overlapping boosts add up (two adjacent +12 dB graphic bands peak near +14.6 dB),
 * so headroom has to come from the combined response, not the largest single gain.
 * Sampled every 1/48 octave plus at each filter's own centre, where peaks sit.
 */
internal fun peakGainDb(
    filters: List<Pair<Double, BiquadCoefficients>>,
    sampleRate: Int,
): Double {
    val top = minOf(AUDIBLE_MAX_HZ, sampleRate * MAX_FC_RATIO)
    val grid = generateSequence(AUDIBLE_MIN_HZ) { it * GRID_STEP }.takeWhile { it <= top }
    val probes = grid + filters.asSequence().map { it.first }.filter { it <= top }
    val peakPower =
        probes.maxOfOrNull { hz -> filters.fold(1.0) { acc, (_, c) -> acc * c.powerAt(hz, sampleRate) } } ?: 1.0
    return 10.0 * log10(peakPower)
}

/** Filters closer to Nyquist than this get numerically fragile; AutoEq's top bands sit well below it. */
private const val MAX_FC_RATIO = 0.49
private const val AUDIBLE_MIN_HZ = 20.0
private const val AUDIBLE_MAX_HZ = 20_000.0
private val GRID_STEP = 2.0.pow(1.0 / 48)
