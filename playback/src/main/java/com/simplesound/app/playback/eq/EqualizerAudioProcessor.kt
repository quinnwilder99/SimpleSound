package com.simplesound.app.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.simplesound.app.data.model.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Runs the equalizer (AutoEq headphone correction + graphic EQ) as a cascade of
 * biquads inside ExoPlayer's audio sink, so it works on any output (wired,
 * Bluetooth, USB) and survives track changes without a session-bound
 * android.media.audiofx effect.
 *
 * [settings] is read on the playback thread at the start of every buffer; when it
 * returns a different instance the coefficients are rebuilt, so UI changes are
 * heard within one buffer. When nothing is active the input is copied through
 * untouched (a bulk copy, so leaving it in the chain costs next to nothing).
 *
 * Runs on the playback thread for the whole time music plays, so it's written for
 * the runtime: each buffer is bulk-copied into a DoubleArray, every filter stage
 * then runs over the whole block with its coefficients and memory in locals, and
 * the result is bulk-copied out. Per-sample ByteBuffer get/put calls (bounds checks
 * and all) were measured on a Pixel at ~20% of a core for a typical setup.
 *
 * Handles 16-bit and float PCM; with float output off (the default) DefaultAudioSink
 * hands every format to custom processors as 16-bit.
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val settings: () -> EqualizerSettings,
) : BaseAudioProcessor() {
    private var applied: EqualizerSettings? = null
    private var sampleRate = 0
    private var channels = 0

    // Per stage: b0, b1, b2, a1, a2.
    private var coefficients = DoubleArray(0)

    // 0 dB stages (most graphic bands, most of the time). Their output equals their input
    // and their memory stays exactly zero, so skipping them changes nothing but the cost.
    private var identity = BooleanArray(0)

    // Transposed direct form II memory, per stage per channel: z1, z2.
    private var state = DoubleArray(0)
    private var stages = 0

    // Headroom gain: [gain] is what's applied now, [targetGain] what the settings call for.
    // When the target rises (a boost lowered, the EQ switched off) each buffer ramps linearly
    // up to it, so level comes back smoothly instead of in per-buffer jumps that click on
    // sustained notes. A falling target is applied at once (see rebuild): clipping is worse.
    private var gain = 1.0
    private var targetGain = 1.0

    // Scratch buffers, grown to the largest buffer seen and then reused.
    private var block = DoubleArray(0)
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val current = settings()
        val formatChanged = sampleRate != inputAudioFormat.sampleRate || channels != inputAudioFormat.channelCount
        if (current !== applied || formatChanged) rebuild(current)
        val out = replaceOutputBuffer(size)
        if (stages == 0 && gain == 1.0 && targetGain == 1.0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        // Decoder output and Media3's own processors use native order; make it explicit
        // since the bulk views below read through the buffer's order.
        inputBuffer.order(ByteOrder.nativeOrder())
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val count = if (isFloat) readFloat(inputBuffer) else read16(inputBuffer)
        applyGain(count)
        runCascade(count)
        if (isFloat) writeFloat(out, count) else write16(out, count)
        out.position(size)
        out.flip()
    }

    override fun onFlush() {
        // A seek or new stream: old filter memory would ring into unrelated audio.
        state.fill(0.0)
    }

    override fun onReset() {
        applied = null
        sampleRate = 0
        channels = 0
        coefficients = DoubleArray(0)
        identity = BooleanArray(0)
        state = DoubleArray(0)
        stages = 0
        gain = 1.0
        targetGain = 1.0
        block = DoubleArray(0)
        shorts = ShortArray(0)
        floats = FloatArray(0)
    }

    private fun rebuild(current: EqualizerSettings) {
        val rate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount
        val filters = if (current.isActive) current.filters() else emptyList()
        val designed =
            filters.mapNotNull {
                    f ->
                biquadCoefficients(f, rate)?.let { Triple(f, f.frequencyHz to it, it) }
            }
        // Keep the filter memory when only gains moved (a slider drag; every band is always
        // in the cascade, see EqualizerSettings.filters), so the change is seamless. Reset it
        // when filters were added/removed (EQ toggled, headphone changed) or the format changed.
        if (designed.size != stages || rate != sampleRate || channelCount != channels) {
            state = DoubleArray(designed.size * channelCount * 2)
        }
        coefficients = DoubleArray(designed.size * COEFFS)
        identity = BooleanArray(designed.size)
        designed.forEachIndexed { i, (filter, _, c) ->
            val o = i * COEFFS
            coefficients[o] = c.b0
            coefficients[o + 1] = c.b1
            coefficients[o + 2] = c.b2
            coefficients[o + 3] = c.a1
            coefficients[o + 4] = c.a2
            identity[i] = filter.gainDb == 0.0
            // A stage that just became 0 dB keeps no memory, exactly as if it had always been
            // one, so it rejoins cleanly when its gain moves again.
            if (identity[i]) state.fill(0.0, i * channelCount * 2, (i + 1) * channelCount * 2)
        }
        stages = designed.size
        // Lower the input by the cascade's highest boost so it can't clip at full scale.
        // Cuts never raise the level, so there's no make-up gain.
        val headroomDb = peakGainDb(designed.map { it.second }, rate).coerceAtLeast(0.0)
        targetGain = 10.0.pow(-headroomDb / 20.0)
        // A new boost takes effect immediately, so its extra headroom must too or the buffer
        // would clip while ramping down. Only rising gain (less headroom needed) is ramped.
        if (targetGain < gain) gain = targetGain
        applied = current
        sampleRate = rate
        channels = channelCount
    }

    private fun read16(input: ByteBuffer): Int {
        val count = input.remaining() / Short.SIZE_BYTES
        if (shorts.size < count) shorts = ShortArray(count)
        if (block.size < count) block = DoubleArray(count)
        input.asShortBuffer().get(shorts, 0, count)
        input.position(input.limit())
        for (i in 0 until count) block[i] = shorts[i] / SHORT_SCALE
        return count
    }

    private fun readFloat(input: ByteBuffer): Int {
        val count = input.remaining() / Float.SIZE_BYTES
        if (floats.size < count) floats = FloatArray(count)
        if (block.size < count) block = DoubleArray(count)
        input.asFloatBuffer().get(floats, 0, count)
        input.position(input.limit())
        for (i in 0 until count) block[i] = floats[i].toDouble()
        return count
    }

    private fun write16(
        out: ByteBuffer,
        count: Int,
    ) {
        for (i in 0 until count) {
            val v = block[i] * SHORT_SCALE
            shorts[i] =
                (
                    if (v > SHORT_MAX) {
                        SHORT_MAX
                    } else if (v < SHORT_MIN) {
                        SHORT_MIN
                    } else {
                        v
                    }
                ).toInt().toShort()
        }
        out.asShortBuffer().put(shorts, 0, count)
    }

    private fun writeFloat(
        out: ByteBuffer,
        count: Int,
    ) {
        for (i in 0 until count) {
            val v = block[i]
            floats[i] =
                (
                    if (v > 1.0) {
                        1.0
                    } else if (v < -1.0) {
                        -1.0
                    } else {
                        v
                    }
                ).toFloat()
        }
        out.asFloatBuffer().put(floats, 0, count)
    }

    /** Headroom gain over the block, ramping per frame to [targetGain] when it's rising. */
    private fun applyGain(count: Int) {
        val frames = count / channels
        if (gain == targetGain) {
            if (gain != 1.0) for (i in 0 until count) block[i] *= gain
            return
        }
        val step = (targetGain - gain) / frames.coerceAtLeast(1)
        var g = gain
        for (f in 0 until frames) {
            g += step
            val base = f * channels
            for (ch in 0 until channels) block[base + ch] *= g
        }
        gain = targetGain
    }

    /** Every non-0 dB stage over the whole block, in order. */
    private fun runCascade(count: Int) {
        for (s in 0 until stages) {
            when {
                identity[s] -> Unit
                channels == 2 -> runStageStereo(s, count)
                else -> for (ch in 0 until channels) runStage(s, ch, count)
            }
        }
    }

    /** One biquad stage over one channel of the block, with everything hot kept in locals. */
    private fun runStage(
        stage: Int,
        channel: Int,
        count: Int,
    ) {
        val c = stage * COEFFS
        val b0 = coefficients[c]
        val b1 = coefficients[c + 1]
        val b2 = coefficients[c + 2]
        val a1 = coefficients[c + 3]
        val a2 = coefficients[c + 4]
        val z = (stage * channels + channel) * 2
        var z1 = state[z]
        var z2 = state[z + 1]
        val samples = block
        var i = channel
        while (i < count) {
            val x = samples[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[i] = y
            i += channels
        }
        state[z] = z1
        state[z + 1] = z2
    }

    /** [runStage] for both channels of a stereo block in one pass: half the loop overhead. */
    private fun runStageStereo(
        stage: Int,
        count: Int,
    ) {
        val c = stage * COEFFS
        val b0 = coefficients[c]
        val b1 = coefficients[c + 1]
        val b2 = coefficients[c + 2]
        val a1 = coefficients[c + 3]
        val a2 = coefficients[c + 4]
        val z = stage * 4 // 2 channels x (z1, z2)
        var l1 = state[z]
        var l2 = state[z + 1]
        var r1 = state[z + 2]
        var r2 = state[z + 3]
        val samples = block
        var i = 0
        while (i < count - 1) {
            val xl = samples[i]
            val yl = b0 * xl + l1
            l1 = b1 * xl - a1 * yl + l2
            l2 = b2 * xl - a2 * yl
            samples[i] = yl
            val xr = samples[i + 1]
            val yr = b0 * xr + r1
            r1 = b1 * xr - a1 * yr + r2
            r2 = b2 * xr - a2 * yr
            samples[i + 1] = yr
            i += 2
        }
        state[z] = l1
        state[z + 1] = l2
        state[z + 2] = r1
        state[z + 3] = r2
    }

    private companion object {
        const val COEFFS = 5
        const val SHORT_SCALE = 32768.0
        const val SHORT_MIN = -32768.0
        const val SHORT_MAX = 32767.0
    }
}
