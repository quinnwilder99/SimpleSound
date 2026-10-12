package com.simplesound.app.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.simplesound.app.data.model.EqFilter
import com.simplesound.app.data.model.EqFilterType
import com.simplesound.app.data.model.EqPreset
import com.simplesound.app.data.model.EqualizerSettings
import com.simplesound.app.data.model.HeadphoneCorrection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerAudioProcessorTest {
    private val rate = 48_000

    // Boosts come with equal headroom (so nothing clips), which shifts the whole curve
    // down: a +6 dB filter plays its peak at 0 dB and everything else at -6 dB. The
    // shape tests below therefore compare frequencies against each other.

    @Test
    fun `peaking filter boosts its centre frequency by its gain relative to the rest`() {
        val settings = single(EqFilter(EqFilterType.PK, 1_000.0, 6.0, 1.0))
        assertEquals(0.0, gainAt(1_000.0, settings), 0.1)
        assertEquals(6.0, gainAt(1_000.0, settings) - gainAt(50.0, settings), 0.1)
    }

    @Test
    fun `peaking filter leaves far-away frequencies flat`() {
        val settings = single(EqFilter(EqFilterType.PK, 8_000.0, 6.0, 2.0))
        assertEquals(0.0, gainAt(100.0, settings) - gainAt(400.0, settings), 0.1)
    }

    @Test
    fun `low shelf boosts bass relative to treble`() {
        val settings = single(EqFilter(EqFilterType.LSC, 200.0, 6.0, 0.7))
        assertEquals(6.0, gainAt(30.0, settings) - gainAt(8_000.0, settings), 0.3)
    }

    @Test
    fun `high shelf cuts treble and leaves bass`() {
        val settings = single(EqFilter(EqFilterType.HSC, 4_000.0, -6.0, 0.7))
        assertEquals(-6.0, gainAt(18_000.0, settings), 0.3)
        assertEquals(0.0, gainAt(100.0, settings), 0.1)
    }

    @Test
    fun `graphic band and headroom preamp combine`() {
        val bands = EqualizerSettings().bandGainsDb.toMutableList().also { it[5] = 4f } // 1 kHz
        val settings = EqualizerSettings(enabled = true, bandGainsDb = bands)
        // +4 dB band with -4 dB headroom: net 0 at 1 kHz, -4 dB far from it.
        assertEquals(0.0, gainAt(1_000.0, settings), 0.1)
        assertEquals(-4.0, gainAt(60.0, settings), 0.2)
    }

    @Test
    fun `overlapping boosts get enough headroom that nothing is louder than the input`() {
        // Adjacent +12 dB bands sum to about +14.6 dB between them; the old max-band rule only reserved 12.
        val settings = bands(4 to 12f, 5 to 12f)
        listOf(500.0, 700.0, 1_000.0).forEach { hz -> assertTrue("$hz Hz", gainAt(hz, settings) <= 0.05) }
    }

    @Test
    fun `full-scale input through a bass boost does not clip`() {
        val settings = EqualizerSettings(enabled = true, bandGainsDb = EqPreset.BASS.gainsDb)
        // Bass preset peaks around 31-62 Hz; sweep its loudest region at 90% of full scale.
        listOf(31.0, 40.0, 50.0, 62.0).forEach { hz ->
            val peakIn = 0.9 * Short.MAX_VALUE
            val loud = ShortArray(rate) { i -> (peakIn * sin(2 * PI * hz * i / rate)).toInt().toShort() }
            val peakOut = run(settings, loud).maxOf { kotlin.math.abs(it.toInt()) }
            // Without headroom this would be ~7 dB over the input (and pinned at full scale).
            assertTrue("$hz Hz: $peakOut > $peakIn", peakOut <= peakIn * 1.01)
        }
    }

    @Test
    fun `peak gain accounts for overlapping filters`() {
        val coefficients =
            bands(4 to 12f, 5 to 12f).filters().map { f -> f.frequencyHz to biquadCoefficients(f, rate)!! }
        assertEquals(14.6, peakGainDb(coefficients, rate), 0.2)
    }

    @Test
    fun `cascade length does not depend on band gains`() {
        // Dragging a band through 0 dB must not add/remove stages (that resets filter memory: a click).
        assertEquals(bands().filters().size, bands(3 to 2f).filters().size)
    }

    @Test
    fun `level comes back up smoothly when headroom is released`() {
        var settings = bands(5 to 12f) // 12 dB of headroom: gain 0.25
        val processor = EqualizerAudioProcessor { settings }
        processor.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        // DC passes a 1 kHz peaking band unchanged, so the output is just input * headroom gain.
        val dc = ShortArray(4_800) { 10_000 }
        assertEquals(2_512.0, feed(processor, dc).last().toDouble(), 30.0)
        settings = settings.copy(enabled = false)
        val out = feed(processor, dc)
        assertEquals("starts where the previous buffer ended", 2_512.0, out.first().toDouble(), 30.0)
        assertEquals("ends at full level", 10_000.0, out.last().toDouble(), 5.0)
        // Ramped over one 2,048-sample buffer: ~3.7 per sample, where a jump would be ~7,500.
        val biggestStep = (1 until out.size).maxOf { kotlin.math.abs(out[it] - out[it - 1]) }
        assertTrue("no jump bigger than a ramp step: $biggestStep", biggestStep <= 5)
        // Fully ramped: from here the disabled EQ is a straight copy again.
        assertArrayEquals(dc, feed(processor, dc))
    }

    @Test
    fun `reconfiguring to a new sample rate and channel count keeps the response right`() {
        val settings = single(EqFilter(EqFilterType.PK, 1_000.0, 6.0, 1.0))
        val processor = EqualizerAudioProcessor { settings }
        processor.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        feed(processor, sine(1_000.0, 4_410))
        // Next track: 48 kHz stereo, as the sink reconfigures between items.
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val mono = sine(1_000.0, rate)
        val stereo = ShortArray(mono.size * 2) { mono[it / 2] }
        val out = feed(processor, stereo)
        val half = stereo.size / 2
        // +6 dB filter with 6 dB headroom: 1 kHz comes out at the input level, on both channels.
        assertEquals(0.0, 20 * log10(rms(out, half) / rms(stereo, half)), 0.15)
    }

    @Test
    fun `stereo channels are filtered independently`() {
        val settings = single(EqFilter(EqFilterType.PK, 1_000.0, 6.0, 1.0))
        val processor = EqualizerAudioProcessor { settings }
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val tone = sine(1_000.0, rate)
        val leftOnly = ShortArray(tone.size * 2) { if (it % 2 == 0) tone[it / 2] else 0 }
        val out = feed(processor, leftOnly)
        val left = ShortArray(tone.size) { out[it * 2] }
        assertTrue("right stays silent", (0 until tone.size).all { out[it * 2 + 1] == 0.toShort() })
        assertEquals(0.0, 20 * log10(rms(left, tone.size / 2) / rms(tone, tone.size / 2)), 0.1)
    }

    @Test
    fun `disabled equalizer passes audio through bit-exact`() {
        val settings = single(EqFilter(EqFilterType.PK, 1_000.0, 6.0, 1.0)).copy(enabled = false)
        val input = sine(1_000.0, 4_800)
        assertArrayEquals(input, run(settings, input))
    }

    @Test
    fun `filters at or above nyquist are skipped`() {
        assertNull(biquadCoefficients(EqFilter(EqFilterType.PK, 30_000.0, 6.0, 1.0), rate))
    }

    private fun bands(vararg gains: Pair<Int, Float>) =
        EqualizerSettings(
            enabled = true,
            bandGainsDb =
                EqPreset.FLAT.gainsDb.toMutableList().also {
                        list ->
                    gains.forEach { (i, g) -> list[i] = g }
                },
        )

    private fun single(filter: EqFilter) =
        EqualizerSettings(
            enabled = true,
            headphone = HeadphoneCorrection("x", "X", "over-ear", "test", 0.0, listOf(filter)),
        )

    /** Steady-state gain in dB applied to a sine at [hz]; measured over the second half, once the filters settle. */
    private fun gainAt(
        hz: Double,
        settings: EqualizerSettings,
    ): Double {
        val input = sine(hz, rate)
        val output = run(settings, input)
        val half = input.size / 2
        return 20 * log10(rms(output, half) / rms(input, half))
    }

    private fun run(
        settings: EqualizerSettings,
        samples: ShortArray,
    ): ShortArray {
        val processor = EqualizerAudioProcessor { settings }
        processor.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        return feed(processor, samples)
    }

    /** Pushes [samples] through an already-configured [processor] and returns its output. */
    private fun feed(
        processor: EqualizerAudioProcessor,
        samples: ShortArray,
    ): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        val out = ShortArray(samples.size)
        var written = 0
        // Feed in chunks, as the audio sink does.
        while (input.hasRemaining()) {
            val chunk = input.slice().order(ByteOrder.nativeOrder())
            chunk.limit(minOf(chunk.remaining(), CHUNK_BYTES))
            input.position(input.position() + chunk.limit())
            processor.queueInput(chunk)
            val o = processor.output
            while (o.hasRemaining()) out[written++] = o.getShort()
        }
        return out
    }

    private fun sine(
        hz: Double,
        count: Int,
    ) = ShortArray(count) { i -> (AMPLITUDE * sin(2 * PI * hz * i / rate)).toInt().toShort() }

    private fun rms(
        samples: ShortArray,
        from: Int,
    ): Double = sqrt((from until samples.size).sumOf { samples[it].toDouble() * samples[it] } / (samples.size - from))

    private companion object {
        const val AMPLITUDE = 8_000.0 // well below full scale so a +6 dB boost doesn't clip
        const val CHUNK_BYTES = 4_096
    }
}
