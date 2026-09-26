package com.simplesound.app.data

import com.simplesound.app.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity rules for merging a MediaStore scan into the stored library; see [reconcileLibrary]. */
class LibraryReconcilerTest {
    private val now = 1_000_000L

    @Test
    fun `unchanged library changes nothing`() {
        val lib = listOf(track(1, "/a.mp3"), track(2, "/b.mp3"))
        val result = reconcileLibrary(lib, lib, now)
        assertEquals(setOf(1L, 2L), result.tracks.map { it.id }.toSet())
        assertTrue(result.missingIds.isEmpty())
        assertFalse(result.changesReferences)
    }

    @Test
    fun `vanished track is kept as missing instead of dropped`() {
        val result = reconcileLibrary(listOf(track(1, "/a.mp3"), track(2, "/b.mp3")), listOf(track(1, "/a.mp3")), now)
        assertEquals(setOf(2L), result.missingIds)
        assertEquals(now, result.tracks.single { it.id == 2L }.missingSinceSec)
        assertFalse(result.changesReferences)
    }

    @Test
    fun `missing track keeps its original missing-since stamp`() {
        val stillGone = track(2, "/b.mp3").copy(missingSinceSec = now - 100)
        val result = reconcileLibrary(listOf(stillGone), emptyList(), now)
        assertEquals(now - 100, result.tracks.single().missingSinceSec)
    }

    @Test
    fun `missing track is dropped once past the grace period`() {
        val longGone = track(2, "/b.mp3").copy(missingSinceSec = now - MISSING_TRACK_RETENTION_SEC - 1)
        val result = reconcileLibrary(listOf(longGone), emptyList(), now)
        assertTrue(result.tracks.isEmpty())
        assertEquals(setOf(2L), result.droppedIds)
    }

    @Test
    fun `returning track is present again`() {
        val gone = track(2, "/b.mp3").copy(missingSinceSec = now - 100)
        val result = reconcileLibrary(listOf(gone), listOf(track(2, "/b.mp3")), now)
        assertEquals(0L, result.tracks.single().missingSinceSec)
        assertTrue(result.missingIds.isEmpty())
    }

    @Test
    fun `re-indexed file is remapped to its new id by path`() {
        val result = reconcileLibrary(listOf(track(5, "/a.mp3")), listOf(track(9, "/a.mp3")), now)
        assertEquals(mapOf(5L to 9L), result.idRemap)
        assertEquals(listOf(9L), result.tracks.map { it.id })
        assertTrue(result.missingIds.isEmpty())
    }

    @Test
    fun `ids swapped by a media rebuild are remapped simultaneously`() {
        val result =
            reconcileLibrary(
                existing = listOf(track(5, "/a.mp3"), track(9, "/b.mp3")),
                scanned = listOf(track(9, "/a.mp3"), track(5, "/b.mp3")),
                nowSec = now,
            )
        assertEquals(mapOf(5L to 9L, 9L to 5L), result.idRemap)
        assertEquals(listOf(9L, 5L, 7L), result.remapIds(listOf(5L, 9L, 7L)))
    }

    @Test
    fun `id reused by a different file drops the old references`() {
        val result = reconcileLibrary(listOf(track(5, "/old.mp3")), listOf(track(5, "/new.mp3")), now)
        assertEquals(setOf(5L), result.droppedIds)
        assertEquals(null, result.resolve(5L))
        // The new file still occupies the id as a present track.
        assertEquals(0L, result.tracks.single { it.id == 5L }.missingSinceSec)
    }

    @Test
    fun `rows without a stored path match by id`() {
        // Rows written before schema v2 have no path yet.
        val result = reconcileLibrary(listOf(track(5, "")), listOf(track(5, "/a.mp3")), now)
        assertFalse(result.changesReferences)
        assertEquals("/a.mp3", result.tracks.single().path)
    }

    @Test
    fun `remapIds removes duplicates created by a remap`() {
        val result = reconcileLibrary(listOf(track(5, "/a.mp3")), listOf(track(9, "/a.mp3")), now)
        assertEquals(listOf(9L, 1L), result.remapIds(listOf(5L, 1L, 9L)))
    }

    @Test
    fun `folderOf keeps the path relative to the storage volume`() {
        assertEquals("Music/Rap", MediaStoreScanner.folderOf("/storage/emulated/0/Music/Rap/a.mp3"))
        assertEquals("Music", MediaStoreScanner.folderOf("/storage/1A2B-3C4D/Music/a.mp3"))
        assertEquals("", MediaStoreScanner.folderOf("/storage/emulated/0/a.mp3"))
    }

    private fun track(
        id: Long,
        path: String,
    ) = TrackEntity(
        id = id,
        title = "t$id",
        artist = "",
        album = "",
        durationMs = 0L,
        uri = "content://media/external/audio/media/$id",
        albumArtUri = null,
        folder = "",
        dateAddedSec = 0L,
        path = path,
    )
}
