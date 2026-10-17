package com.simplesound.app.playback.crossfade

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * Decodes a stretch of a local track to PCM with the platform decoder and reduces it
 * to a [LoudnessEnvelope]. Runs once per track edge, off the main thread, a minute
 * before it's needed: MediaCodec decodes MP3/AAC/FLAC far faster than real time, so
 * a 45 s tail costs a fraction of a second of CPU -- nothing like keeping a player
 * awake to measure it live.
 */
internal object LoudnessScanner {
    const val WINDOW_MS = 50

    private const val TIMEOUT_US = 10_000L
    private const val SHORT_SCALE = 1.0 / 32_768.0
    private const val US_PER_MS = 1_000L
    private const val US_PER_S = 1_000_000L

    /**
     * Envelope of [uri] from [fromMs] up to [toMs] (or the end of the audio, if
     * sooner), or null if it can't be decoded. [isActive] is polled so a cancelled
     * caller stops the decode promptly.
     *
     * Any decoder/extractor failure just means "no analysis": the fade falls back to
     * fixed timing, so there's nothing useful to do with the exception.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun scan(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        isActive: () -> Boolean,
    ): LoudnessEnvelope? {
        if (toMs <= fromMs) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val track =
                (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (fromMs > 0) extractor.seekTo(fromMs * US_PER_MS, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val acc = Accumulator(fromMs, toMs, format)
            decode(extractor, decoder, toMs * US_PER_MS, acc, isActive)
            acc.envelope()
        } catch (e: Exception) {
            null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractor.release()
        }
    }

    private fun decode(
        extractor: MediaExtractor,
        codec: MediaCodec,
        toUs: Long,
        acc: Accumulator,
        isActive: () -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (isActive()) {
            if (!inputDone) inputDone = feedInput(extractor, codec, toUs)
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                acc.setFormat(codec.outputFormat)
            } else if (outIndex >= 0) {
                if (info.size > 0) acc.add(codec.getOutputBuffer(outIndex)!!, info)
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    /** Queues the next compressed sample, or end-of-stream past [toUs]; true once the
     *  end-of-stream has been queued. */
    private fun feedInput(
        extractor: MediaExtractor,
        codec: MediaCodec,
        toUs: Long,
    ): Boolean {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return false
        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
        val time = extractor.sampleTime
        val ended = size < 0 || time > toUs
        if (ended) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            codec.queueInputBuffer(inIndex, 0, size, time, 0)
            extractor.advance()
        }
        return ended
    }

    /** Sums squared samples into [WINDOW_MS] bins keyed by each frame's media time. */
    private class Accumulator(
        private val fromMs: Long,
        toMs: Long,
        format: MediaFormat,
    ) {
        private val windows = ((toMs - fromMs) / WINDOW_MS).toInt().coerceAtLeast(1)
        private val sumSq = DoubleArray(windows)
        private val count = LongArray(windows)
        private var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var isFloat = false

        fun setFormat(format: MediaFormat) {
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            isFloat =
                format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        }

        fun add(
            raw: ByteBuffer,
            info: MediaCodec.BufferInfo,
        ) {
            val buf = raw.duplicate().order(ByteOrder.nativeOrder())
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val bytesPerSample = if (isFloat) 4 else 2
            val frames = info.size / (bytesPerSample * channels)
            val shorts = if (isFloat) null else buf.asShortBuffer()
            val floats = if (isFloat) buf.asFloatBuffer() else null
            for (f in 0 until frames) {
                val tMs = (info.presentationTimeUs + f * US_PER_S / sampleRate) / US_PER_MS
                val w = ((tMs - fromMs) / WINDOW_MS).toInt()
                if (w >= windows) return
                var frameSq = 0.0
                repeat(channels) {
                    val s = floats?.get()?.toDouble() ?: (shorts!!.get() * SHORT_SCALE)
                    frameSq += s * s
                }
                if (w >= 0) {
                    sumSq[w] += frameSq / channels
                    count[w]++
                }
            }
        }

        fun envelope(): LoudnessEnvelope? {
            // Trailing windows with no samples are past the end of the audio.
            val filled = count.indexOfLast { it > 0 } + 1
            if (filled == 0) return null
            val db =
                FloatArray(filled) { i ->
                    val meanSq = if (count[i] == 0L) 0.0 else sumSq[i] / count[i]
                    if (meanSq <= 0.0) {
                        CrossfadePlanner.MIN_DB
                    } else {
                        (10 * log10(meanSq)).toFloat().coerceAtLeast(CrossfadePlanner.MIN_DB)
                    }
                }
            return LoudnessEnvelope(fromMs, WINDOW_MS, db)
        }
    }
}
