package com.simplesound.app.data.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class Id3TagTest {
    @get:Rule val tmp = TemporaryFolder()

    // A fake MPEG frame stream; the editor must never touch these bytes.
    private val audio = ByteArray(5000) { (it * 31 + 7).toByte() }

    @Test
    fun `in-place edit keeps the audio and other frames byte for byte`() {
        val apic = frame24("APIC", ByteArray(300) { 9 })
        val tag = tag(4, frame24("TIT2", text("Old title")) + apic, padding = 200)
        val file = mp3(tag + audio)

        apply(file, TagEdits(title = "New title"))

        val bytes = file.readBytes()
        assertEquals("tag size unchanged -> written in place", tag.size + audio.size, bytes.size)
        assertArrayEquals(audio, bytes.copyOfRange(tag.size, bytes.size))
        val frames = framesOf(bytes)
        assertEquals("New title", frames["TIT2"])
        assertTrue("APIC kept", bytes.containsSlice(apic))
    }

    @Test
    fun `growing tag shifts the audio intact and leaves padding for next time`() {
        val tag = tag(3, frame23("TIT2", text("a")), padding = 0)
        val file = mp3(tag + audio)

        apply(file, TagEdits(title = "A much longer title than before", artist = "Someone"))

        val bytes = file.readBytes()
        val newTagSize = Id3Tag.existingTagSize(bytes)
        assertTrue(newTagSize > tag.size)
        assertArrayEquals(audio, bytes.copyOfRange(newTagSize, bytes.size))
        val frames = framesOf(bytes)
        assertEquals("A much longer title than before", frames["TIT2"])
        assertEquals("Someone", frames["TPE1"])
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.startsWith("tagedit") })
    }

    @Test
    fun `file without a tag gets a new v23 tag`() {
        val file = mp3(audio)
        apply(file, TagEdits(title = "Fresh"))
        val bytes = file.readBytes()
        assertEquals(3, bytes[3].toInt())
        assertEquals("Fresh", framesOf(bytes)["TIT2"])
        assertArrayEquals(audio, bytes.copyOfRange(Id3Tag.existingTagSize(bytes), bytes.size))
    }

    @Test
    fun `non latin titles use utf16 on v23 and utf8 on v24`() {
        val v3 = mp3(tag(3, frame23("TIT2", text("x")), padding = 100) + audio)
        apply(v3, TagEdits(title = "Chạy Ngay Đi"))
        assertEquals("Chạy Ngay Đi", framesOf(v3.readBytes())["TIT2"])

        val v4 = mp3(tag(4, frame24("TIT2", text("x")), padding = 100) + audio)
        apply(v4, TagEdits(title = "夜に駆ける"))
        assertEquals("夜に駆ける", framesOf(v4.readBytes())["TIT2"])
    }

    @Test
    fun `untouched fields are left alone and blank removes the frame`() {
        val tpe1 = frame24("TPE1", text("Artist"))
        val file = mp3(tag(4, frame24("TIT2", text("T")) + tpe1 + frame24("TALB", text("Album")), 100) + audio)
        apply(file, TagEdits(album = ""))
        val bytes = file.readBytes()
        val frames = framesOf(bytes)
        assertEquals("T", frames["TIT2"])
        assertTrue(bytes.containsSlice(tpe1))
        assertNull(frames["TALB"])
    }

    @Test
    fun `id3v1 tail is patched too`() {
        val v1 = ByteArray(128).also { "TAG".toByteArray().copyInto(it) }
        "Old".toByteArray().copyInto(v1, 3)
        val file = mp3(tag(3, frame23("TIT2", text("Old")), padding = 64) + audio + v1)
        apply(file, TagEdits(title = "Newer"))
        val bytes = file.readBytes()
        assertEquals("Newer", String(bytes, bytes.size - 125, 30, Charsets.ISO_8859_1).trimEnd('\u0000'))
    }

    @Test
    fun `v22 tags are supported`() {
        val f = "TT2".toByteArray() + byteArrayOf(0, 0, 4) + text("abc")
        val file = mp3(tag(2, f, padding = 50) + audio)
        apply(file, TagEdits(title = "xyz"))
        val bytes = file.readBytes()
        assertTrue(bytes.containsSlice("TT2".toByteArray() + byteArrayOf(0, 0, 4, 0) + "xyz".toByteArray()))
    }

    @Test
    fun `unsynchronised tag is refused without touching the file`() {
        val tag = tag(3, frame23("TIT2", text("x")), padding = 10).also { it[5] = 0x80.toByte() }
        val original = tag + audio
        val file = mp3(original)
        try {
            apply(file, TagEdits(title = "y"))
            fail("expected UnsupportedTagException")
        } catch (_: UnsupportedTagException) {
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `itunes style non syncsafe v24 frame sizes are read`() {
        // 200-byte frame whose plain size (0x000000C8) has the top bit of a byte set.
        val body = ByteArray(200) { 1 }
        val bad = "PRIV".toByteArray() + byteArrayOf(0, 0, 0, 0xC8.toByte(), 0, 0) + body
        val file = mp3(tag(4, frame24("TIT2", text("x")) + bad, padding = 20) + audio)
        apply(file, TagEdits(title = "y"))
        val bytes = file.readBytes()
        assertEquals("y", framesOf(bytes)["TIT2"])
        assertTrue(bytes.containsSlice(bad))
    }

    // ---------- helpers ----------

    private fun mp3(bytes: ByteArray): File = tmp.newFile().also { it.writeBytes(bytes) }

    private fun apply(
        file: File,
        edits: TagEdits,
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            Id3Tag.applyToFile(raf.channel, raf.channel, edits, tmp.root)
        }
    }

    private fun text(s: String) = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1)

    private fun frame23(
        id: String,
        data: ByteArray,
    ) = id.toByteArray() + int32(data.size) + byteArrayOf(0, 0) + data

    private fun frame24(
        id: String,
        data: ByteArray,
    ) = id.toByteArray() + syncSafe(data.size) + byteArrayOf(0, 0) + data

    private fun tag(
        major: Int,
        frames: ByteArray,
        padding: Int,
    ): ByteArray {
        val body = frames + ByteArray(padding)
        return "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, 0) + syncSafe(body.size) + body
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun syncSafe(v: Int) =
        byteArrayOf(
            (v shr 21 and 0x7F).toByte(),
            (v shr 14 and 0x7F).toByte(),
            (v shr 7 and 0x7F).toByte(),
            (v and 0x7F).toByte(),
        )

    /** Decodes the text frames of a v2.3/2.4 tag at the start of [file]. */
    private fun framesOf(file: ByteArray): Map<String, String> {
        val major = file[3].toInt()
        val end = Id3Tag.existingTagSize(file)
        val out = mutableMapOf<String, String>()
        var pos = 10
        while (pos + 10 <= end && file[pos] != 0.toByte()) {
            val id = String(file, pos, 4, Charsets.ISO_8859_1)
            val b = file.copyOfRange(pos + 4, pos + 8).map { it.toInt() and 0xFF }
            val size =
                if (major == 4) {
                    (b[0] shl 21) or (b[1] shl 14) or (b[2] shl 7) or b[3]
                } else {
                    (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
                }
            if (id.startsWith("T")) {
                val data = file.copyOfRange(pos + 11, pos + 10 + size)
                out[id] =
                    when (file[pos + 10].toInt()) {
                        0 -> String(data, Charsets.ISO_8859_1)
                        1 -> String(data, Charsets.UTF_16)
                        else -> String(data, Charsets.UTF_8)
                    }
            }
            pos += 10 + size
        }
        return out
    }

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean {
        val hay = this
        return (0..hay.size - slice.size).any { i -> slice.indices.all { hay[i + it] == slice[it] } }
    }
}
