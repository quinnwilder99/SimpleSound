package com.simplesound.app.data

import android.content.Context
import android.content.SharedPreferences
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Single source of truth for the music library and playlists.
 *
 * Tracks are scanned from the device MediaStore on app start (see [loadDeviceLibrary]),
 * so they are not persisted here. User-created playlists and the set of favorite
 * single tracks, however, are persisted to a small SharedPreferences blob so that they
 * survive app restarts. v0.1 keeps the runtime data in memory flows (so UI is reactive)
 * with the disk layer acting purely as a save/restore snapshot on [load] and on every
 * mutation.
 */
object MusicRepository {

    private const val PREFS_NAME = "simplesound_playlists"
    private const val KEY_PLAYLISTS = "user_playlists"
    private const val KEY_FAVORITE_TRACKS = "favorite_track_ids"
    private const val KEY_INITIALIZED = "initialized"

    private lateinit var prefs: SharedPreferences

    private val _tracks = MutableStateFlow(SampleData.tracks)
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    // Initial values are the sample seed; [load] overrides them from disk when present.
    private val _userPlaylists = MutableStateFlow(SampleData.userPlaylists())
    val userPlaylists: StateFlow<List<Playlist>> = _userPlaylists.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow(SampleData.favoriteTrackIds)
    val favoriteTrackIds: StateFlow<Set<Long>> = _favoriteTrackIds.asStateFlow()

    private val NATIVE_LIMIT = 100

    // ---------- Init / persistence ----------

    /**
     * Call once from [com.simplesound.app.SimpleSoundApp.onCreate] before any screen
     * touches the repository. Restores user playlists + favorite track ids from disk.
     * On the very first launch (no saved state) the sample seed is persisted so that
     * subsequent launches match the first-run state.
     */
    @Synchronized
    fun load(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_INITIALIZED, false)) {
            val saved = decodePlaylists(prefs.getString(KEY_PLAYLISTS, null))
            if (saved != null) _userPlaylists.value = saved

            val savedFav = decodeFavoriteTrackIds(prefs.getString(KEY_FAVORITE_TRACKS, null))
            if (savedFav != null) _favoriteTrackIds.value = savedFav
        } else {
            // First run: persist the seed so nothing "disappears" on the next launch.
            persistUserPlaylists()
            persistFavoriteTrackIds()
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        }
        // We just restored _userPlaylists / _favoriteTrackIds from disk (or seeded
        // them). The favorites tab flow was initialised eagerly with the sample seed
        // before this method ran, so recompute it now — otherwise the Favorites tab
        // would show stale seed data (wrong hearted playlists / wrong order) until the
        // next mutation, e.g. after a full app restart.
        recomputeFavorites()
    }

    /** App is starting up — restore library + playlists from disk/scan. */
    fun load(context: Context, scan: Boolean = true) {
        load(context)
        if (scan) {
            val scanned = runCatching { MediaStoreScanner.scan(context) }.getOrDefault(emptyList())
            if (scanned.isNotEmpty()) {
                _tracks.value = scanned
                recomputeFavorites()
            }
        }
    }

    private fun persistUserPlaylists() {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(KEY_PLAYLISTS, encodePlaylists(_userPlaylists.value)).apply()
    }

    private fun persistFavoriteTrackIds() {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(KEY_FAVORITE_TRACKS, encodeFavoriteTrackIds(_favoriteTrackIds.value)).apply()
    }

    // ---------- Library loading ----------

    /** Replace sample tracks with real device audio, if any were found. */
    fun loadDeviceLibrary(context: Context) {
        val scanned = runCatching { MediaStoreScanner.scan(context) }.getOrDefault(emptyList())
        if (scanned.isNotEmpty()) {
            _tracks.value = scanned
            // The "Favorite tracks" native playlist is derived from the track set,
            // so a media-scan change must refresh the favorites tab too.
            recomputeFavorites()
        }
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

    fun searchTracks(query: String): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return _tracks.value
        val needle = q.lowercase()
        return _tracks.value.filter {
            it.title.lowercase().contains(needle) ||
                it.artist.lowercase().contains(needle) ||
                it.album.lowercase().contains(needle)
        }
    }

    // ---------- Favorites (single tracks) ----------

    fun isFavorite(trackId: Long): Boolean = trackId in _favoriteTrackIds.value

    fun toggleFavoriteTrack(trackId: Long) {
        _favoriteTrackIds.value = _favoriteTrackIds.value.toMutableSet().apply {
            if (!add(trackId)) remove(trackId)
        }
        persistFavoriteTrackIds()
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

    private val _favoritesTab = MutableStateFlow(computeFavoritesTab())
    val favoritesTabPlaylists: StateFlow<List<Playlist>> = _favoritesTab.asStateFlow()

    private fun recomputeFavorites() {
        _favoritesTab.value = computeFavoritesTab()
    }

    private fun computeFavoritesTab(): List<Playlist> {
        val hearted = _userPlaylists.value
            .filter { it.favorited }
            .sortedByDescending { it.favoritedAt }
        return listOf(favoriteTracksPlaylist()) + hearted
    }

    // ---------- Playlist mutations ----------

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): String {
        val id = "user-" + UUID.randomUUID().toString()
        _userPlaylists.value = _userPlaylists.value + Playlist(id, name.ifBlank { "New playlist" }, trackIds)
        persistUserPlaylists()
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
        persistUserPlaylists()
        recomputeFavorites()
    }

    /** Permanently remove a track from the library, all playlists, and favorites. */
    fun deleteTrack(trackId: Long) {
        _tracks.value = _tracks.value.filterNot { it.id == trackId }
        _favoriteTrackIds.value = _favoriteTrackIds.value.filterNot { it == trackId }.toSet()
        persistFavoriteTrackIds()
        _userPlaylists.value = _userPlaylists.value.map { pl ->
            pl.copy(trackIds = pl.trackIds.filterNot { it == trackId })
        }
        persistUserPlaylists()
        recomputeFavorites()
    }

    /** Permanently remove several tracks at once from the library, playlists, and favorites. */
    fun deleteTracks(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val ids = trackIds.toSet()
        _tracks.value = _tracks.value.filterNot { it.id in ids }
        _favoriteTrackIds.value = _favoriteTrackIds.value.filterNot { it in ids }.toSet()
        persistFavoriteTrackIds()
        _userPlaylists.value = _userPlaylists.value.map { pl ->
            pl.copy(trackIds = pl.trackIds.filterNot { it in ids })
        }
        persistUserPlaylists()
        recomputeFavorites()
    }

    /** Persist a custom drag-reorder of the user playlists. */
    fun reorderPlaylists(orderedIds: List<String>) {
        val byId = _userPlaylists.value.associateBy { it.id }
        _userPlaylists.value = orderedIds.mapNotNull { byId[it] }
        persistUserPlaylists()
    }

    fun playlistById(id: String): Playlist? =
        _userPlaylists.value.firstOrNull { it.id == id }
            ?: nativePlaylists().firstOrNull { it.id == id }
            ?: favoriteTracksPlaylist().takeIf { it.id == id }

    private inline fun update(id: String, transform: (Playlist) -> Playlist) {
        _userPlaylists.value = _userPlaylists.value.map { if (it.id == id) transform(it) else it }
        persistUserPlaylists()
        recomputeFavorites()
    }

    // ---------- Persistence encoding ----------
    //
    // Compact, dependency-free string format. Record separator = '\u0001',
    // field separator = '\u0002'. Playlist names/cover uris are unlikely to
    // contain these control chars; if they do, the chars are stripped on encode.
    // Fields per playlist: id, kind.name, name, coverUri|null, favorited(0|1),
    // favoritedAt, then the comma-joined track ids.

    private fun encodePlaylists(playlists: List<Playlist>): String =
        playlists.joinToString("\u0001") { pl ->
            val fields = listOf(
                pl.id,
                pl.kind.name,
                sanitize(pl.name),
                pl.coverUri ?: "",
                if (pl.favorited) "1" else "0",
                pl.favoritedAt.toString(),
                pl.trackIds.joinToString(",")
            )
            fields.joinToString("\u0002")
        }

    private fun decodePlaylists(raw: String?): List<Playlist>? {
        if (raw.isNullOrEmpty()) return null
        return raw.split("\u0001").mapNotNull { record ->
            val f = record.split("\u0002")
            if (f.size < 7) return@mapNotNull null
            val id = f[0]
            val kind = runCatching { PlaylistKind.valueOf(f[1]) }.getOrDefault(PlaylistKind.USER)
            val name = f[2]
            val cover = f[3].takeIf { it.isNotEmpty() }
            val favorited = f[4] == "1"
            val favoritedAt = f[5].toLongOrNull() ?: 0L
            val trackIds = f[6].split(",").mapNotNull { it.toLongOrNull() }
            Playlist(
                id = id,
                name = name,
                trackIds = trackIds,
                coverUri = cover,
                kind = kind,
                favorited = favorited,
                favoritedAt = favoritedAt
            )
        }
    }

    private fun encodeFavoriteTrackIds(ids: Set<Long>): String =
        ids.joinToString(",")

    private fun decodeFavoriteTrackIds(raw: String?): Set<Long>? {
        if (raw == null) return null
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun sanitize(s: String): String =
        s.replace("\u0001", "").replace("\u0002", "")
}