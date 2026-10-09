package com.simplesound.app.data.tags

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * New values for a track's tags. `null` leaves that field exactly as it is in the
 * file (so an untouched multi-value artist frame isn't flattened); a blank string
 * removes the frame.
 */
data class TagEdits(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null
}

/** The file's tag uses a feature we don't rewrite safely, so it was left untouched. */
class UnsupportedTagException(message: String) : IOException(message)

/**
 * Minimal ID3v2.2/2.3/2.4 (+ ID3v1) editor for the title/artist/album text frames.
 *
 * Every frame we don't edit -- artwork, lyrics, comments, ReplayGain, anything
 * unknown -- is copied byte for byte. Anything we can't parse with certainty
 * (tag-wide unsynchronisation, v2.2 compression, a frame overrunning the tag)
 * throws [UnsupportedTagException] *before* a single byte of the file is written,
 * rather than guessing and corrupting someone's music.
 */
internal object Id3Tag {
    private const val HEADER = 10
    private const val FOOTER = 10
    private const val V1_SIZE = 128

    /** Room left after the frames when a tag has to grow, so the next edit fits in place. */
    internal const val GROW_PADDING = 2048

    private const val FLAG_UNSYNC = 0x80
    private const val FLAG_EXT_HEADER = 0x40 // v2.3/2.4; on v2.2 this bit means compression
    private const val FLAG_EXPERIMENTAL = 0x20
    private const val FLAG_FOOTER = 0x10 // v2.4 only

    private val LATIN1 = Charsets.ISO_8859_1

    private class Frame(val id: String, val raw: ByteArray)

    /** What [rebuild] keeps from the old tag. */
    private class Existing(val major: Int, val flags: Int, val frames: List<Frame>)

    private fun unsupported(message: String): Nothing = throw UnsupportedTagException(message)

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    /**
     * Size of the ID3v2 tag at the start of a file (header + body + v2.4 footer),
     * from its first [HEADER] bytes; 0 when the file has no ID3v2 tag.
     */
    fun existingTagSize(head: ByteArray): Int {
        if (head.size < HEADER || !head.startsWith("ID3")) return 0
        val major = head[3].toInt()
        if (major !in 2..4) unsupported("ID3v2.$major tags aren't supported")
        val body = syncSafe(head, 6) ?: unsupported("Corrupt ID3 header")
        val footer = if (major == 4 && head[5].toInt() and FLAG_FOOTER != 0) FOOTER else 0
        return HEADER + body + footer
    }

    /**
     * Builds a replacement for [existing] (the whole old tag, or empty for none) with
     * [edits] applied. When the result fits in [fitInto] bytes it is padded to exactly
     * that size, so the caller can overwrite the old tag in place without moving the
     * audio after it.
     */
    fun rebuild(
        existing: ByteArray,
        edits: TagEdits,
        fitInto: Int = existing.size,
    ): ByteArray {
        val old = if (existing.isEmpty()) Existing(major = 3, flags = 0, frames = emptyList()) else parse(existing)
        val major = old.major
        val ids = idsFor(major)
        val edited = ids.filterIndexed { i, _ -> edits.fieldAt(i) != null }.toSet()

        val frames = ByteArrayOutputStream()
        for (i in ids.indices) {
            val value = edits.fieldAt(i)
            if (!value.isNullOrBlank()) frames.write(textFrame(ids[i], value.trim(), major))
        }
        old.frames.filterNot { it.id in edited }.forEach { frames.write(it.raw) }

        val content = frames.toByteArray()
        val minSize = HEADER + content.size
        val total = if (minSize <= fitInto) fitInto else minSize + GROW_PADDING
        if (total - HEADER >= 1 shl 28) unsupported("ID3 tag too large")

        // The extended header and footer are optional and dropped (a v2.3 extended
        // header can carry a CRC of the old frames, which would now be wrong).
        val outFlags = if (major == 2) 0 else old.flags and FLAG_EXPERIMENTAL
        val out = ByteArray(total)
        "ID3".toByteArray(LATIN1).copyInto(out)
        out[3] = major.toByte()
        out[4] = 0
        out[5] = outFlags.toByte()
        putSyncSafe(out, 6, total - HEADER)
        content.copyInto(out, HEADER)
        return out
    }

    private fun parse(tag: ByteArray): Existing {
        val major = tag[3].toInt()
        val flags = tag[5].toInt() and 0xFF
        if (flags and FLAG_UNSYNC != 0) unsupported("Unsynchronised ID3 tags aren't supported")
        if (major == 2 && flags and FLAG_EXT_HEADER != 0) unsupported("Compressed ID3v2.2 tags aren't supported")
        val end = HEADER + (syncSafe(tag, 6) ?: unsupported("Corrupt ID3 header"))
        if (end > tag.size) unsupported("Truncated ID3 tag")
        val start =
            when {
                major == 2 || flags and FLAG_EXT_HEADER == 0 -> HEADER
                major == 3 -> HEADER + 4 + int32(tag, HEADER)
                else -> HEADER + (syncSafe(tag, HEADER) ?: unsupported("Corrupt extended header"))
            }
        if (start !in HEADER..end) unsupported("Corrupt extended header")
        return Existing(major, flags, parseFrames(tag, start, end, major))
    }

    /**
     * Returns [tail] (a file's last 128 bytes) with [edits] applied if it is an ID3v1
     * tag, else null. v1 fields are Latin-1 and 30 bytes, so values are truncated and
     * unencodable characters become '?'.
     */
    fun patchV1(
        tail: ByteArray,
        edits: TagEdits,
    ): ByteArray? {
        if (tail.size != V1_SIZE || !tail.startsWith("TAG")) return null
        val out = tail.copyOf()
        putV1Field(out, 3, edits.title)
        putV1Field(out, 33, edits.artist)
        putV1Field(out, 63, edits.album)
        return out
    }

    private fun putV1Field(
        tag: ByteArray,
        offset: Int,
        value: String?,
    ) {
        if (value == null) return
        val bytes = encodeLossy(value.trim(), LATIN1)
        for (i in 0 until 30) tag[offset + i] = if (i < bytes.size) bytes[i] else 0
    }

    private fun idsFor(major: Int): List<String> =
        if (major == 2) listOf("TT2", "TP1", "TAL") else listOf("TIT2", "TPE1", "TALB")

    private fun TagEdits.fieldAt(i: Int): String? =
        when (i) {
            0 -> title
            1 -> artist
            else -> album
        }

    private fun parseFrames(
        tag: ByteArray,
        start: Int,
        end: Int,
        major: Int,
    ): List<Frame> {
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        val frames = mutableListOf<Frame>()
        var pos = start
        while (pos + headerLen <= end && tag[pos] != 0.toByte()) {
            val id = String(tag, pos, idLen, LATIN1)
            if (!id.all(::isIdChar)) unsupported("Unreadable ID3 frame")
            val size =
                when (major) {
                    2 -> (tag[pos + 3].u() shl 16) or (tag[pos + 4].u() shl 8) or tag[pos + 5].u()
                    3 -> int32(tag, pos + 4)
                    else -> v24FrameSize(tag, pos, end)
                }
            val next = pos + headerLen + size
            if (size < 0 || next > end) unsupported("ID3 frame overruns the tag")
            frames += Frame(id, tag.copyOfRange(pos, next))
            pos = next
        }
        return frames
    }

    /**
     * v2.4 frame sizes are sync-safe, but older iTunes wrote plain big-endian ones.
     * Prefer sync-safe; fall back to plain when only that lands on the next frame.
     */
    private fun v24FrameSize(
        tag: ByteArray,
        pos: Int,
        end: Int,
    ): Int {
        val plain = int32(tag, pos + 4)
        val safe = syncSafe(tag, pos + 4)
        return when {
            safe == null -> plain
            safe == plain || landsOnFrame(tag, pos + 10 + safe, end) -> safe
            landsOnFrame(tag, pos + 10 + plain, end) -> plain
            else -> safe
        }
    }

    /** True if [pos] is the tag's end, the start of its padding, or a plausible frame header. */
    private fun landsOnFrame(
        tag: ByteArray,
        pos: Int,
        end: Int,
    ): Boolean =
        when {
            pos == end -> true
            pos < 0 || pos > end -> false
            tag[pos] == 0.toByte() -> true
            pos + 4 > end -> false
            else -> (0 until 4).all { isIdChar(tag[pos + it].toInt().toChar()) }
        }

    private fun textFrame(
        id: String,
        value: String,
        major: Int,
    ): ByteArray {
        val latin1 = LATIN1.newEncoder().canEncode(value)
        val body = ByteArrayOutputStream()
        when {
            latin1 -> {
                body.write(0)
                body.write(value.toByteArray(LATIN1))
            }
            major == 4 -> {
                body.write(3)
                body.write(value.toByteArray(Charsets.UTF_8))
            }
            else -> {
                body.write(1)
                body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // UTF-16LE BOM
                body.write(value.toByteArray(Charsets.UTF_16LE))
            }
        }
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(LATIN1))
        when (major) {
            2 -> out.write(byteArrayOf((data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
            3 -> out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            else -> ByteArray(4).also { putSyncSafe(it, 0, data.size) }.let(out::write)
        }
        if (major != 2) out.write(byteArrayOf(0, 0)) // frame flags
        out.write(data)
        return out.toByteArray()
    }

    private fun encodeLossy(
        value: String,
        charset: Charset,
    ): ByteArray {
        val encoder =
            charset.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPLACE)
        val buf = encoder.encode(CharBuffer.wrap(value))
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private fun isIdChar(c: Char) = c in 'A'..'Z' || c in '0'..'9'

    private fun Byte.u() = toInt() and 0xFF

    private fun int32(
        b: ByteArray,
        off: Int,
    ): Int = (b[off].u() shl 24) or (b[off + 1].u() shl 16) or (b[off + 2].u() shl 8) or b[off + 3].u()

    /** Null when any byte has its top bit set, i.e. the value isn't sync-safe. */
    private fun syncSafe(
        b: ByteArray,
        off: Int,
    ): Int? {
        if ((0 until 4).any { b[off + it].u() and 0x80 != 0 }) return null
        return (b[off].u() shl 21) or (b[off + 1].u() shl 14) or (b[off + 2].u() shl 7) or b[off + 3].u()
    }

    private fun putSyncSafe(
        b: ByteArray,
        off: Int,
        value: Int,
    ) {
        b[off] = ((value shr 21) and 0x7F).toByte()
        b[off + 1] = ((value shr 14) and 0x7F).toByte()
        b[off + 2] = ((value shr 7) and 0x7F).toByte()
        b[off + 3] = (value and 0x7F).toByte()
    }

    // ---------- File level ----------

    /**
     * Applies [edits] to the MP3 behind [read]/[write] (two channels on the same open
     * file). When the new tag fits in the old one's space -- the usual case, thanks to
     * padding -- only the tag bytes are overwritten. Otherwise the audio is first
     * copied to a backup in [scratchDir], then the file is rewritten as new tag +
     * audio; if that fails part-way the original is written back from the backup.
     */
    fun applyToFile(
        read: FileChannel,
        write: FileChannel,
        edits: TagEdits,
        scratchDir: File,
    ) {
        if (edits.isEmpty) return
        val fileSize = read.size()
        val head = readAt(read, 0, minOf(HEADER.toLong(), fileSize).toInt())
        val oldSize = existingTagSize(head)
        if (oldSize > fileSize) unsupported("Truncated ID3 tag")
        val oldTag = if (oldSize > 0) readAt(read, 0, oldSize) else ByteArray(0)
        val newTag = rebuild(oldTag, edits, fitInto = oldSize)

        // v1 is computed up front too, so nothing is written unless both tags parsed.
        val v1Patch =
            if (fileSize - oldSize >= V1_SIZE) patchV1(readAt(read, fileSize - V1_SIZE, V1_SIZE), edits) else null

        if (newTag.size == oldSize) {
            writeAt(write, 0, newTag)
        } else {
            rewriteWithNewTag(read, write, oldTag, newTag, scratchDir)
        }
        if (v1Patch != null) writeAt(write, write.size() - V1_SIZE, v1Patch)
        write.force(true)
    }

    private fun rewriteWithNewTag(
        read: FileChannel,
        write: FileChannel,
        oldTag: ByteArray,
        newTag: ByteArray,
        scratchDir: File,
    ) {
        val fileSize = read.size()
        val audioSize = fileSize - oldTag.size
        val backup = File.createTempFile("tagedit", ".audio", scratchDir)
        try {
            RandomAccessFile(backup, "rw").channel.use { bak ->
                copy(read, oldTag.size.toLong(), bak, 0, audioSize)
                if (bak.size() != audioSize) throw IOException("Backup copy was incomplete")
                try {
                    writeAt(write, 0, newTag)
                    copy(bak, 0, write, newTag.size.toLong(), audioSize)
                    write.truncate(newTag.size + audioSize)
                } catch (e: IOException) {
                    runCatching {
                        writeAt(write, 0, oldTag)
                        copy(bak, 0, write, oldTag.size.toLong(), audioSize)
                        write.truncate(fileSize)
                    }
                    throw e
                }
            }
        } finally {
            backup.delete()
        }
    }

    private fun readAt(
        ch: FileChannel,
        pos: Long,
        len: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(len)
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position()) < 0) throw IOException("Unexpected end of file")
        }
        return buf.array()
    }

    private fun writeAt(
        ch: FileChannel,
        pos: Long,
        bytes: ByteArray,
    ) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf, pos + buf.position())
    }

    private fun copy(
        from: FileChannel,
        fromPos: Long,
        to: FileChannel,
        toPos: Long,
        len: Long,
    ) {
        val buf = ByteBuffer.allocate(64 * 1024)
        var done = 0L
        while (done < len) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), len - done).toInt())
            val n = from.read(buf, fromPos + done)
            if (n < 0) throw IOException("Unexpected end of file")
            buf.flip()
            while (buf.hasRemaining()) to.write(buf, toPos + done + buf.position())
            done += n
        }
    }
}
