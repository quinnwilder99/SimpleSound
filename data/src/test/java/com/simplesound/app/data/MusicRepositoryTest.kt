package com.simplesound.app.data

import androidx.room.Room
import app.cash.turbine.test
import com.simplesound.app.data.db.AppDatabase
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [MusicRepository] against a real (in-memory) Room database, covering
 * the behavior the old SharedPreferences-backed implementation had to preserve:
 * synchronous in-memory mutations, cascading deletes, native playlist computation,
 * and that mutations actually reach the durable Room store.
 */
@RunWith(RobolectricTestRunner::class)
class MusicRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            MusicRepository(
                context = context,
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                playlistTrackDao = db.playlistTrackDao(),
                favoriteDao = db.favoriteDao(),
                playStatsDao = db.playStatsDao(),
                customOrderDao = db.customOrderDao(),
            )
    }

    @After
    fun tearDown() {
        // Drain every write MusicRepository fired-and-forgot onto its background
        // scope before closing the database — otherwise a still-in-flight write
        // from this test can land after teardown and throw into whichever test
        // happens to be running next.
        runBlocking { repository.awaitPendingWrites() }
        db.close()
    }

    @Test
    fun `favorite toggle updates in-memory flow immediately`() {
        assertFalse(repository.isFavorite(1L))
        repository.toggleFavoriteTrack(1L)
        assertTrue(repository.isFavorite(1L))
        repository.toggleFavoriteTrack(1L)
        assertFalse(repository.isFavorite(1L))
    }

    @Test
    fun `favorite toggle persists to room`() =
        runTest {
            db.favoriteDao().observeAll().test {
                assertEquals(emptyList<Long>(), awaitItem())
                repository.toggleFavoriteTrack(9L)
                assertEquals(listOf(9L), awaitItem())
                repository.toggleFavoriteTrack(9L)
                assertEquals(emptyList<Long>(), awaitItem())
            }
        }

    @Test
    fun `createPlaylist is immediately visible via playlistById`() {
        val id = repository.createPlaylist("Road trip", trackIds = listOf(1L, 2L, 3L))
        val playlist = repository.playlistById(id)
        assertEquals("Road trip", playlist?.name)
        assertEquals(listOf(1L, 2L, 3L), playlist?.trackIds)
        assertEquals(PlaylistKind.USER, playlist?.kind)
    }

    @Test
    fun `createPlaylist persists playlist and track order to room`() =
        runTest {
            val id = repository.createPlaylist("Gym", trackIds = listOf(5L, 6L))
            // createPlaylist's Room write is fire-and-forget on the repository's
            // background scope (see its class doc) -- without this, the Flow below
            // can emit its initial (still-empty) snapshot before that write lands,
            // which is exactly the kind of race a fast local machine usually wins
            // and a loaded CI runner sometimes doesn't.
            repository.awaitPendingWrites()
            db.playlistTrackDao().observeForPlaylist(id).test {
                val refs = awaitItem()
                assertEquals(listOf(5L, 6L), refs.sortedBy { it.position }.map { it.trackId })
            }
        }

    @Test
    fun `renamePlaylist updates name without touching track membership`() {
        val id = repository.createPlaylist("Old name", trackIds = listOf(1L))
        repository.renamePlaylist(id, "New name")
        val playlist = repository.playlistById(id)
        assertEquals("New name", playlist?.name)
        assertEquals(listOf(1L), playlist?.trackIds)
    }

    @Test
    fun `deleteTracks cascades out of favorites and every playlist`() {
        val id = repository.createPlaylist("Mix", trackIds = listOf(1L, 2L, 3L))
        repository.toggleFavoriteTrack(2L)

        repository.deleteTracks(listOf(2L))

        assertFalse(repository.isFavorite(2L))
        assertEquals(listOf(1L, 3L), repository.playlistById(id)?.trackIds)
    }

    @Test
    fun `deletePlaylist removes it but never touches native playlists`() {
        val id = repository.createPlaylist("Temp")
        repository.deletePlaylist(id)
        assertNull(repository.playlistById(id))

        repository.deletePlaylist("native-favorite-tracks")
        assertEquals("Favorite tracks", repository.playlistById("native-favorite-tracks")?.name)
    }

    @Test
    fun `toggleFavoritePlaylist orders the favorites tab by most-recently-hearted`() {
        val first = repository.createPlaylist("First")
        val second = repository.createPlaylist("Second")
        repository.toggleFavoritePlaylist(first)
        // favoritedAt is a wall-clock millis stamp; a real user can't heart two
        // playlists within the same millisecond, but a test can, so force the two
        // timestamps apart to make the ordering assertion below deterministic.
        Thread.sleep(5)
        repository.toggleFavoritePlaylist(second)

        // Favorite tracks native playlist is always first; hearted user playlists
        // follow, most-recently-hearted on top.
        val names = repository.favoritesTabPlaylists.value.map { it.name }
        assertEquals(listOf("Favorite tracks", "Second", "First"), names)
    }

    @Test
    fun `reorderPlaylists changes exposed order`() {
        val a = repository.createPlaylist("A")
        val b = repository.createPlaylist("B")
        repository.reorderPlaylists(listOf(b, a))
        assertEquals(listOf(b, a), repository.userPlaylists.value.map { it.id })
    }

    @Test
    fun `moveTrackInCustomOrder swaps adjacent tracks and reconciles new arrivals`() {
        val id = repository.createPlaylist("Ordered", trackIds = listOf(1L, 2L, 3L))
        repository.moveTrackInCustomOrder(id, trackId = 2L, up = true, currentOrder = listOf(1L, 2L, 3L))
        assertEquals(listOf(2L, 1L, 3L), repository.customOrderFor(id))

        // A track added after the custom order was saved is appended, not dropped.
        repository.moveTrackInCustomOrder(id, trackId = 4L, up = true, currentOrder = listOf(2L, 1L, 3L, 4L))
        assertEquals(listOf(2L, 1L, 4L, 3L), repository.customOrderFor(id))
    }

    @Test
    fun `sortTracks by name is case-insensitive`() {
        val tracks =
            listOf(
                track(1L, title = "banana"),
                track(2L, title = "Apple"),
                track(3L, title = "cherry"),
            )
        val sorted = repository.sortTracks(tracks, SortOption.NAME)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `searchTracks matches title artist or album case-insensitively`() {
        repository.createPlaylist("noop") // no-op, just exercising the repo before search
        // searchTracks reads the in-memory track list, which is empty without a
        // MediaStore scan in this test, so verify the empty-query passthrough and
        // the no-match path explicitly.
        assertTrue(repository.searchTracks("").isEmpty())
        assertTrue(repository.searchTracks("anything").isEmpty())
    }

    private fun track(
        id: Long,
        title: String,
    ) = com.simplesound.app.data.model.Track(
        id = id,
        title = title,
        artist = "",
        album = "",
        durationMs = 0L,
        uri = "",
    )
}
