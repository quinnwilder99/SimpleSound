package com.simplesound.app.data

import android.content.Context
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Single in-memory source of truth for the music library and playlists.
 *
 * v0.1 keeps everything in memory (seeded from [SampleData], overlaid with real
 * device audio from [MediaStoreScanner] when permission is granted). Swapping this
 * for a Room-backed implementation later requires no UI changes — screens depend on
 * the flows below, not on storage.
 */
object MusicRepository {

    private val _tracks = MutableStateFlow(SampleData.tracks)
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _userPlaylists = MutableStateFlow(SampleData.userPlaylists())
    val userPlaylists: StateFlow<List<Playlist>> = _userPlaylists.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow(SampleData.favoriteTrackIds)
    val favoriteTrackIds: StateFlow<Set<Long>> = _favoriteTrackIds.asStateFlow()

    private val NATIVE_LIMIT = 100

    // ---------- Library loading ----------

    /** Replace sample tracks with real device audio, if any were found. */
    fun loadDeviceLibrary(context: Context) {
        val scanned = runCatching { MediaStoreScanner.scan(context) }.getOrDefault(emptyList())
        if (scanned.isNotEmpty()) _tracks.value = scanned
    }

    fun trackById(id: Long): Track? = _tracks.value.firstOrNull { it.id == id }
    fun tracksByIds(ids: List<Long>): List<Track> {
        val map = _tracks.value.associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    fun sortedTracks(option: SortOption): List<Track> = _tracks.value.sortedWith(
        when (option) {
            SortOption.DATE_ADDED -> compareByDescending { it.dateAddedSec }
            SortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortOption.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artistOrUnknown }
            SortOption.LENGTH -> compareBy { it.durationMs }
        }
    )

    // ---------- Favorites (single tracks) ----------

    fun isFavorite(trackId: Long): Boolean = trackId in _favoriteTrackIds.value

    fun toggleFavoriteTrack(trackId: Long) {
        _favoriteTrackIds.value = _favoriteTrackIds.value.toMutableSet().apply {
            if (!add(trackId)) remove(trackId)
        }
        recomputeFavorites()
    }

    /** The always-present "Favorite tracks" playlist (kind = FAVORITE_TRACKS). */
    fun favoriteTracksPlaylist(): Playlist = Playlist(
        id = "native-favorite-tracks",
        name = "Favorite tracks",
        trackIds = _favoriteTrackIds.value.toList(),
        kind = PlaylistKind.FAVORITE_TRACKS
    )

    // ---------- Native (computed) playlists ----------

    fun nativePlaylists(): List<Playlist> {
        val all = _tracks.value
        val recentlyAdded = all.sortedByDescending { it.dateAddedSec }
        val mostPlayed = all.sortedByDescending { it.playCount }
        val recentlyPlayed = all.filter { it.lastPlayedSec > 0 }.sortedByDescending { it.lastPlayedSec }
        return listOf(
            Playlist("native-recently-added", "Recently added",
                recentlyAdded.take(NATIVE_LIMIT).map { it.id }, kind = PlaylistKind.RECENTLY_ADDED),
            Playlist("native-most-played", "Most played",
                mostPlayed.take(NATIVE_LIMIT).map { it.id }, kind = PlaylistKind.MOST_PLAYED),
            Playlist("native-recently-played", "Recently played",
                recentlyPlayed.take(NATIVE_LIMIT).map { it.id }, kind = PlaylistKind.RECENTLY_PLAYED),
            favoriteTracksPlaylist(),
        )
    }

    // ---------- Favorites tab contents ----------

    /**
     * The playlists shown on the Favorites tab: the Favorite tracks playlist first
     * (always present), followed by every user playlist the user hearted. Recomputed
     * whenever favorites or playlists change.
     */
    private val _favoritesTab = MutableStateFlow(computeFavoritesTab())
    val favoritesTabPlaylists: StateFlow<List<Playlist>> = _favoritesTab.asStateFlow()

    private fun recomputeFavorites() {
        _favoritesTab.value = computeFavoritesTab()
    }

    private fun computeFavoritesTab(): List<Playlist> {
        // "Favorite tracks" always sits at position 1. The remaining hearted playlists
        // behave like a stack: the most recently hearted rises to the top.
        val hearted = _userPlaylists.value
            .filter { it.favorited }
            .sortedByDescending { it.favoritedAt }
        return listOf(favoriteTracksPlaylist()) + hearted
    }

    // ---------- Playlist mutations ----------

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): String {
        val id = "user-" + UUID.randomUUID().toString()
        _userPlaylists.value = _userPlaylists.value + Playlist(id, name.ifBlank { "New playlist" }, trackIds)
        return id
    }

    fun renamePlaylist(id: String, name: String) = update(id) { it.copy(name = name.ifBlank { it.name }) }

    fun setPlaylistCover(id: String, coverUri: String?) = update(id) { it.copy(coverUri = coverUri) }

    fun addTracksToPlaylist(id: String, trackIds: List<Long>) = update(id) {
        it.copy(trackIds = (it.trackIds + trackIds).distinct())
    }

    fun removeTrackFromPlaylist(id: String, trackId: Long) = update(id) {
        it.copy(trackIds = it.trackIds - trackId)
    }

    fun toggleFavoritePlaylist(id: String) {
        update(id) { pl ->
            val newFavorited = !pl.favorited
            pl.copy(
                favorited = newFavorited,
                favoritedAt = if (newFavorited) System.currentTimeMillis() else 0L
            )
        }
        recomputeFavorites()
    }

    fun deletePlaylist(id: String) {
        _userPlaylists.value = _userPlaylists.value.filterNot { it.id == id }
        recomputeFavorites()
    }

    /** Permanently remove a track from the library, all playlists, and favorites. */
    fun deleteTrack(trackId: Long) {
        _tracks.value = _tracks.value.filterNot { it.id == trackId }
        _favoriteTrackIds.value = _favoriteTrackIds.value.filterNot { it == trackId }.toSet()
        _userPlaylists.value = _userPlaylists.value.map { pl ->
            pl.copy(trackIds = pl.trackIds.filterNot { it == trackId })
        }
        recomputeFavorites()
    }

    /** Persist a custom drag-reorder of the user playlists. */
    fun reorderPlaylists(orderedIds: List<String>) {
        val byId = _userPlaylists.value.associateBy { it.id }
        _userPlaylists.value = orderedIds.mapNotNull { byId[it] }
    }

    fun playlistById(id: String): Playlist? =
        _userPlaylists.value.firstOrNull { it.id == id }
            ?: nativePlaylists().firstOrNull { it.id == id }
            ?: favoriteTracksPlaylist().takeIf { it.id == id }

    private inline fun update(id: String, transform: (Playlist) -> Playlist) {
        _userPlaylists.value = _userPlaylists.value.map { if (it.id == id) transform(it) else it }
        recomputeFavorites()
    }
}