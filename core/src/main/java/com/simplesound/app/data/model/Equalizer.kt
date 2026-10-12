package com.simplesound.app.data.model

/** Biquad filter shapes used by AutoEq's ParametricEQ.txt (and by the graphic EQ bands). */
enum class EqFilterType {
    /** Peaking (bell). */
    PK,

    /** Low shelf. */
    LSC,

    /** High shelf. */
    HSC,
}

/** One parametric EQ filter: centre/corner [frequencyHz], [gainDb] boost or cut, and [q]. */
data class EqFilter(
    val type: EqFilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * An AutoEq headphone correction: the filters that flatten [name]'s frequency
 * response to AutoEq's target. [preampDb] is AutoEq's suggested headroom for these
 * filters alone; playback computes its own from the whole cascade (correction plus
 * graphic EQ), since the two can boost the same frequencies. [source] is the
 * reviewer whose measurement it was computed from.
 */
data class HeadphoneCorrection(
    val id: String,
    val name: String,
    val type: String,
    val source: String,
    val preampDb: Double,
    val filters: List<EqFilter>,
)

/** Preset curves for the graphic EQ, gains in dB per [EqualizerSettings.BAND_FREQUENCIES_HZ] band. */
enum class EqPreset(
    val label: String,
    val gainsDb: List<Float>,
) {
    FLAT("Flat", List(10) { 0f }),
    BASS("Bass boost", listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 4.5f, 5f)),
    VOCAL("Vocal", listOf(-2f, -1.5f, -1f, 0f, 2f, 3f, 3f, 1.5f, 0f, -1f)),
    LOUDNESS("Loudness", listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1.5f, 3f, 4f)),
}

/**
 * Everything the equalizer needs to process audio. [headphone] (AutoEq correction)
 * and [bandGainsDb] (the user's 10-band graphic EQ) stack: correction first, then
 * taste on top. Nothing is applied while [enabled] is false.
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val headphone: HeadphoneCorrection? = null,
    val bandGainsDb: List<Float> = EqPreset.FLAT.gainsDb,
) {
    /** True when processing would change the signal at all. */
    val isActive: Boolean
        get() = enabled && (headphone != null || bandGainsDb.any { it != 0f })

    /** The preset whose curve [bandGainsDb] currently matches, if any. */
    val preset: EqPreset?
        get() = EqPreset.entries.firstOrNull { it.gainsDb == bandGainsDb }

    /**
     * Every filter to run, in order: the headphone correction, then all graphic EQ
     * bands. 0 dB bands are included on purpose (they're an exact pass-through): it
     * keeps the cascade the same length while a slider is dragged through 0, so the
     * processor never has to reset filter memory mid-song (an audible click).
     */
    fun filters(): List<EqFilter> {
        val bands =
            BAND_FREQUENCIES_HZ.zip(bandGainsDb) { hz, gain -> EqFilter(EqFilterType.PK, hz, gain.toDouble(), BAND_Q) }
        return headphone?.filters.orEmpty() + bands
    }

    companion object {
        /** Octave-spaced graphic EQ bands, 31 Hz to 16 kHz. */
        val BAND_FREQUENCIES_HZ = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

        /** Q of a one-octave-wide band, so adjacent bands blend without gaps or big overlaps. */
        const val BAND_Q = 1.41

        const val MAX_BAND_GAIN_DB = 12f
    }
}
