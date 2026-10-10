package com.simplesound.app.data

import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSortingTest {
    // In custom (drag) order, as the repository publishes them.
    private val playlists =
        listOf(
            Playlist(id = "b", name = "beats", createdAt = 1L),
            Playlist(id = "c", name = "Chill", createdAt = 1_700_000_000_000L),
            Playlist(id = "a", name = "Acoustic", createdAt = 2L),
        )

    private fun ids(sort: SortOption) = sortPlaylists(playlists, sort).map { it.id }

    @Test
    fun `name sort ignores case`() {
        assertEquals(listOf("a", "b", "c"), ids(SortOption.NAME))
    }

    @Test
    fun `date added sort puts the newest first, with backfilled stamps older than real ones`() {
        assertEquals(listOf("c", "a", "b"), ids(SortOption.DATE_ADDED))
    }

    @Test
    fun `custom order keeps the published drag order`() {
        assertEquals(listOf("b", "c", "a"), ids(SortOption.CUSTOM_ORDER))
    }
}
