package com.simplesound.app.data

import android.content.Context
import android.content.SharedPreferences
import com.simplesound.app.data.db.CustomOrderDao
import com.simplesound.app.data.db.CustomOrderEntity
import com.simplesound.app.data.db.FavoriteDao
import com.simplesound.app.data.db.FavoriteTrackEntity
import com.simplesound.app.data.db.PlayStatsDao
import com.simplesound.app.data.db.PlayStatsEntity
import com.simplesound.app.data.db.PlaylistDao
import com.simplesound.app.data.db.PlaylistTrackDao
import com.simplesound.app.data.db.TrackDao
import com.simplesound.app.data.db.toDomain
import com.simplesound.app.data.db.toEntity
import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the music library and playlists.
 *
 * Tracks are scanned from the device MediaStore (see [loadDeviceLibrary], and the
 * auto-sync worker in `data/sync/LibrarySyncWorker.kt`) and persisted into the Room
 * [com.simplesound.app.data.db.AppDatabase]; user playlists, favorites, per-track
 * play stats, and per-playlist custom ordering are persisted there too, each in its
 * own indexed table (see `data/db/`). The most recently persisted state of the
 * last-played track and temp queue remain a small SharedPreferences snapshot — that
 * data isn't relational, it's just a "resume point" blob, so Room would add
 * ceremony without benefit there.
 *
 * Design: every mutation updates the in-memory [StateFlow]s *synchronously* (so a
 * caller like `createPlaylist(...)` can immediately look the new playlist up via
 * [playlistById], and the UI never waits on a disk round-trip to react) and kicks
 * off the matching Room write on [scope] in the background. Room is therefore the
 * durable, queryable, indexed store; the in-memory flows are a write-through cache
 * that gives the UI instant, always-consistent reads. [restoreFromDatabase] rebuilds
 * that cache from Room once, at construction time.
 */
@Singleton
class MusicRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trackDao: TrackDao,
        private val playlistDao: PlaylistDao,
        private val playlistTrackDao: PlaylistTrackDao,
        private val favoriteDao: FavoriteDao,
        private val playStatsDao: PlayStatsDao,
        private val customOrderDao: CustomOrderDao,
    ) {
        private companion object {
            const val PREFS_NAME = "simplesound_playlists"
            const val KEY_LEGACY_INITIALIZED = "initialized"
            const val KEY_LEGACY_PLAYLISTS = "user_playlists"
            const val KEY_LEGACY_FAVORITE_TRACKS = "favorite_track_ids"
            const val KEY_LEGACY_CUSTOM_ORDERS = "custom_orders"
            const val KEY_LEGACY_PLAY_STATS = "play_stats"
            const val KEY_ROOM_MIGRATED = "room_migrated"

            const val KEY_LAST_PLAYED_TRACK_ID = "last_played_track_id"
            const val KEY_LAST_PLAYED_TITLE = "last_played_title"
            const val KEY_LAST_PLAYED_ARTIST = "last_played_artist"
            const val KEY_LAST_PLAYED_URI = "last_played_uri"
            const val KEY_LAST_PLAYED_ALBUM_ART = "last_played_album_art"
            const val KEY_LAST_PLAYED_DURATION = "last_played_duration_ms"
            const val KEY_LAST_PLAYED_POSITION = "last_played_position_ms"
            const val KEY_QUEUE_TITLE = "last_queue_title"
            const val KEY_QUEUE_TRACK_IDS = "last_queue_track_ids"
            const val KEY_QUEUE_INDEX = "last_queue_index"
            const val KEY_SHUFFLE_ENABLED = "last_shuffle_enabled"
            const val KEY_REPEAT_MODE = "last_repeat_mode"

            const val NATIVE_LIMIT = 100
        }

        /** Background scope for Room writes triggered by synchronous in-memory mutations. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Waits for every write currently in flight on [scope] to finish. Not used by
         * production code (mutations are fire-and-forget by design — see the class doc)
         * — only by tests that need to assert against Room after calling a mutator,
         * so a launched write can't leak past the end of one test and race the next.
         */
        internal suspend fun awaitPendingWrites() {
            scope.coroutineContext.job.children.toList().joinAll()
        }

        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private val _tracks = MutableStateFlow<List<Track>>(emptyList())
        val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

        /**
         * Per-track play stats (playCount, lastPlayedSec), keyed by track id. Kept
         * separately from [_tracks] because the track list itself is rebuilt from
         * scratch on every MediaStore scan ([loadDeviceLibrary]) — without this
         * side table, every rescan (e.g. every app cold start) would silently wipe
         * out play counts and "Recently played"/"Most played" would always be
         * empty. Merged back onto freshly scanned tracks via [applyPlayStats].
         */
        private var playStats: MutableMap<Long, Pair<Int, Long>> = mutableMapOf()

        /** In-memory mirror of [CustomOrderEntity] rows; see [customOrderFor]. */
        private var customOrders: MutableMap<String, List<Long>> = mutableMapOf()

        private val _userPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
        val userPlaylists: StateFlow<List<Playlist>> = _userPlaylists.asStateFlow()

        private val _favoriteTrackIds = MutableStateFlow<Set<Long>>(emptySet())
        val favoriteTrackIds: StateFlow<Set<Long>> = _favoriteTrackIds.asStateFlow()

        // Declared before `init` below (which calls recomputeFavorites() via
        // restoreFromDatabase()) because Kotlin runs property initializers and init
        // blocks in textual order — recomputeFavorites() would otherwise NPE writing
        // to a not-yet-initialized _favoritesTab.
        private val _favoritesTab = MutableStateFlow(computeFavoritesTab())
        val favoritesTabPlaylists: StateFlow<List<Playlist>> = _favoritesTab.asStateFlow()

        init {
            // Blocking is deliberate and matches the pre-Room design ("call load()
            // before any screen touches the repository"): SimpleSoundApp field-injects
            // this repository, which Hilt constructs before Application.onCreate()'s
            // body runs, so this is the app's one guaranteed synchronous bootstrap
            // point. The dataset here (playlists/favorites/stats — not the full
            // library) is small, so the local-SQLite read-back is fast.
            runBlocking(Dispatchers.IO) {
                migrateLegacyPrefsIfNeeded()
                restoreFromDatabase()
            }
        }

        // ---------- Init / persistence ----------

        private suspend fun restoreFromDatabase() {
            val statEntities = playStatsDao.getAll()
            playStats = statEntities.associate { it.trackId to (it.playCount to it.lastPlayedSec) }.toMutableMap()
            _tracks.value = applyPlayStats(trackDao.observeAll().first().map { it.toDomain() })

            _favoriteTrackIds.value = favoriteDao.getAll().toSet()

            val playlistEntities = playlistDao.getAll().sortedBy { it.position }
            val crossRefsByPlaylist = playlistTrackDao.getAll().groupBy { it.playlistId }
            _userPlaylists.value =
                playlistEntities.map { pe ->
                    val trackIds = crossRefsByPlaylist[pe.id].orEmpty().sortedBy { it.position }.map { it.trackId }
                    pe.toDomain(trackIds)
                }

            customOrders =
                customOrderDao.getAll()
                    .associate {
                        it.playlistId to
                            it.orderedTrackIdsCsv.split(",").mapNotNull {
                                    id ->
                                id.toLongOrNull()
                            }
                    }
                    .toMutableMap()

            recomputeFavorites()
        }

        /**
         * One-time migration from the pre-Room SharedPreferences blob into the Room
         * tables. No-ops on every launch after the first once [KEY_ROOM_MIGRATED] is
         * set, and no-ops entirely on a fresh install (nothing to migrate).
         */
        private suspend fun migrateLegacyPrefsIfNeeded() {
            if (prefs.getBoolean(KEY_ROOM_MIGRATED, false)) return
            if (prefs.getBoolean(KEY_LEGACY_INITIALIZED, false)) {
                val legacyPlaylists =
                    decodeLegacyPlaylists(prefs.getString(KEY_LEGACY_PLAYLISTS, null))
                        .orEmpty()
                        // Drop legacy sample-seed playlists that earlier builds persisted on
                        // first run (e.g. "GOAT", "Classical") — never real user data.
                        .filterNot { it.id.startsWith("seed-") }
                legacyPlaylists.forEachIndexed { index, pl ->
                    playlistDao.insert(pl.toEntity(position = index))
                    playlistTrackDao.replaceForPlaylist(pl.id, pl.trackIds)
                }

                decodeLegacyFavoriteTrackIds(prefs.getString(KEY_LEGACY_FAVORITE_TRACKS, null))
                    .orEmpty()
                    .forEach { favoriteDao.add(FavoriteTrackEntity(it)) }

                decodeLegacyCustomOrders(prefs.getString(KEY_LEGACY_CUSTOM_ORDERS, null))
                    .forEach { (playlistId, ids) ->
                        customOrderDao.upsert(CustomOrderEntity(playlistId, ids.joinToString(",")))
                    }

                decodeLegacyPlayStats(prefs.getString(KEY_LEGACY_PLAY_STATS, null))
                    .forEach { (id, stat) -> playStatsDao.upsert(PlayStatsEntity(id, stat.first, stat.second)) }

                prefs.edit()
                    .remove(KEY_LEGACY_PLAYLISTS)
                    .remove(KEY_LEGACY_FAVORITE_TRACKS)
                    .remove(KEY_LEGACY_CUSTOM_ORDERS)
                    .remove(KEY_LEGACY_PLAY_STATS)
                    .remove(KEY_LEGACY_INITIALIZED)
                    .apply()
            }
            prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
        }

        // ---------- Last played track (persistence) ----------
        //
        // The mini player survives app restarts by remembering the most recently
        // played track. We persist a *full snapshot* (id, title, artist, uri,
        // albumArtUri, durationMs, positionMs) — not just the id — so the bar can
        // reappear immediately on launch with the correct timeline, even before the
        // MediaStore scan finishes loading the real library. The id is stored
        // separately so the caller can refresh the snapshot from the live library
        // once it is available. This snapshot is a small, non-relational "resume
        // point" blob, so it stays in SharedPreferences rather than moving to Room.

        /**
         * Persist a snapshot of the most recently played track (or clear with null).
         * Also persists the track's [positionMs] so the mini player / Now Playing
         * screen can restore the correct timeline after an app restart, and so
         * playback can resume from where it left off.
         */
        fun saveLastPlayedTrack(
            track: Track?,
            positionMs: Long = -1L,
        ) {
            val e = prefs.edit()
            if (track == null) {
                e.remove(KEY_LAST_PLAYED_TRACK_ID)
                    .remove(KEY_LAST_PLAYED_TITLE)
                    .remove(KEY_LAST_PLAYED_ARTIST)
                    .remove(KEY_LAST_PLAYED_URI)
                    .remove(KEY_LAST_PLAYED_ALBUM_ART)
                    .remove(KEY_LAST_PLAYED_DURATION)
                    .remove(KEY_LAST_PLAYED_POSITION)
                    .apply()
                return
            }
            e.putLong(KEY_LAST_PLAYED_TRACK_ID, track.id)
                .putString(KEY_LAST_PLAYED_TITLE, track.title)
                .putString(KEY_LAST_PLAYED_ARTIST, track.artist)
                .putString(KEY_LAST_PLAYED_URI, track.uri)
                .putString(KEY_LAST_PLAYED_ALBUM_ART, track.albumArtUri)
                .putLong(KEY_LAST_PLAYED_DURATION, track.durationMs.coerceAtLeast(0L))
                .putLong(KEY_LAST_PLAYED_POSITION, positionMs.coerceAtLeast(0L))
                .apply()
        }

        /** The persisted id of the last played track, or -1 if none was ever saved. */
        fun lastPlayedTrackId(): Long = prefs.getLong(KEY_LAST_PLAYED_TRACK_ID, -1L)

        /**
         * The persisted last-played track snapshot, or null if none was saved.
         * This is a lightweight reconstruction (id + title + artist + uri + art +
         * duration) so the mini player can render immediately on launch; callers may
         * upgrade it to the full live [Track] via [trackById] once the library has
         * loaded.
         */
        fun lastPlayedTrack(): Track? {
            val id = prefs.getLong(KEY_LAST_PLAYED_TRACK_ID, -1L)
            if (id < 0L) return null
            return Track(
                id = id,
                title = prefs.getString(KEY_LAST_PLAYED_TITLE, null) ?: "",
                artist = prefs.getString(KEY_LAST_PLAYED_ARTIST, null) ?: "",
                album = "",
                durationMs = prefs.getLong(KEY_LAST_PLAYED_DURATION, 0L),
                uri = prefs.getString(KEY_LAST_PLAYED_URI, null) ?: "",
                albumArtUri = prefs.getString(KEY_LAST_PLAYED_ALBUM_ART, null),
            )
        }

        /** The persisted playback position (ms) of the last played track, or 0. */
        fun lastPlayedPosition(): Long = prefs.getLong(KEY_LAST_PLAYED_POSITION, 0L)

        // ---------- Queue (persistence) ----------
        //
        // The temp queue (the ordered list of tracks currently loaded into the player,
        // the playing index, and a label describing where it came from) is persisted as
        // a comma-joined list of track ids so it can be rebuilt from the live library
        // after an app restart. Only ids survive — the full [Track] objects are
        // re-resolved from the in-memory library once it has loaded, so any track that
        // was deleted between sessions is simply dropped.

        /** Persist a snapshot of the current temp queue (or clear it with null/empty). */
        fun saveQueue(
            title: String?,
            trackIds: List<Long>?,
            index: Int,
        ) {
            val e = prefs.edit()
            if (trackIds.isNullOrEmpty()) {
                e.remove(KEY_QUEUE_TITLE)
                    .remove(KEY_QUEUE_TRACK_IDS)
                    .remove(KEY_QUEUE_INDEX)
                    .apply()
                return
            }
            e.putString(KEY_QUEUE_TITLE, title ?: "")
                .putString(KEY_QUEUE_TRACK_IDS, trackIds.joinToString(","))
                .putInt(KEY_QUEUE_INDEX, index.coerceAtLeast(0))
                .apply()
        }

        /** The persisted queue title, or "" if none was saved. */
        fun lastQueueTitle(): String = prefs.getString(KEY_QUEUE_TITLE, "") ?: ""

        /** The persisted queue track ids, or an empty list if none was saved. */
        fun lastQueueTrackIds(): List<Long> {
            val raw = prefs.getString(KEY_QUEUE_TRACK_IDS, null) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split(",").mapNotNull { it.toLongOrNull() }
        }

        /** The persisted queue index, or -1 if none was saved. */
        fun lastQueueIndex(): Int = prefs.getInt(KEY_QUEUE_INDEX, -1)

        // ---------- Playback modes (shuffle / repeat) ----------
        //
        // Like the last-played track and queue above, shuffle and repeat only live on
        // the in-memory Media3 [androidx.media3.common.Player] otherwise, so they're
        // silently lost whenever the OS kills the process (e.g. the app is backgrounded
        // for a while) rather than just the Activity. Persisted here so they survive
        // that the same way the queue and resume position already do.

        /** Persist whether shuffle is currently on. */
        fun saveShuffleEnabled(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, enabled).apply()
        }

        /** The persisted shuffle state, or false (off) if none was saved. */
        fun lastShuffleEnabled(): Boolean = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)

        /** Persist the current repeat mode: 0 = off, 1 = repeat all, 2 = repeat one. */
        fun saveRepeatMode(mode: Int) {
            prefs.edit().putInt(KEY_REPEAT_MODE, mode).apply()
        }

        /** The persisted repeat mode (0 = off, 1 = all, 2 = one), or 0 if none was saved. */
        fun lastRepeatMode(): Int = prefs.getInt(KEY_REPEAT_MODE, 0)

        // ---------- Library loading ----------

        /** Replace sample/stale tracks with a fresh MediaStore scan, if any were found. */
        suspend fun loadDeviceLibrary(context: Context) {
            val scanned = runCatching { MediaStoreScanner.scan(context) }.getOrDefault(emptyList())
            if (scanned.isNotEmpty()) {
                trackDao.replaceAll(scanned.map { it.toEntity() })
                _tracks.value = applyPlayStats(scanned)
                // The "Favorite tracks" native playlist is derived from the track set,
                // so a media-scan change must refresh the favorites tab too.
                recomputeFavorites()
            }
        }

        /** Overlay persisted [playStats] onto a freshly scanned track list. */
        private fun applyPlayStats(tracks: List<Track>): List<Track> {
            if (playStats.isEmpty()) return tracks
            return tracks.map { track ->
                val stats = playStats[track.id] ?: return@map track
                track.copy(playCount = stats.first, lastPlayedSec = stats.second)
            }
        }

        fun trackById(id: Long): Track? = _tracks.value.firstOrNull { it.id == id }

        fun tracksByIds(ids: List<Long>): List<Track> {
            val map = _tracks.value.associateBy { it.id }
            return ids.mapNotNull { map[it] }
        }

        /**
         * The comparator for every [SortOption] except [SortOption.CUSTOM_ORDER],
         * which has no meaning outside a specific playlist's saved order (see
         * [sortPlaylistTracks]) and is resolved differently by each caller below.
         */
        private fun comparatorFor(option: SortOption): Comparator<Track> =
            when (option) {
                SortOption.DATE_ADDED, SortOption.CUSTOM_ORDER -> compareByDescending { it.dateAddedSec }
                SortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                SortOption.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artistOrUnknown }
                SortOption.LENGTH -> compareBy { it.durationMs }
            }

        fun sortedTracks(option: SortOption): List<Track> = _tracks.value.sortedWith(comparatorFor(option))

        /** Sort an arbitrary list of tracks (e.g. a playlist's contents) by [option]. */
        fun sortTracks(
            tracks: List<Track>,
            option: SortOption,
        ): List<Track> =
            // No playlist context to resolve a custom order against here, so keep
            // the input order rather than falling back to date-added like
            // sortedTracks() does.
            if (option == SortOption.CUSTOM_ORDER) tracks else tracks.sortedWith(comparatorFor(option))

        /**
         * Sort a playlist's tracks by [option]. When [option] is [SortOption.CUSTOM_ORDER],
         * the tracks are ordered by the user-saved custom order for [playlistId] (any
         * tracks not present in the saved order are appended in their natural order).
         */
        fun sortPlaylistTracks(
            playlistId: String,
            tracks: List<Track>,
            option: SortOption,
        ): List<Track> {
            if (option == SortOption.CUSTOM_ORDER) {
                val order = customOrderFor(playlistId)
                if (order.isEmpty()) return tracks
                val index = order.withIndex().associate { (i, id) -> id to i }
                return tracks.sortedBy { index[it.id] ?: Int.MAX_VALUE }
            }
            return sortTracks(tracks, option)
        }

        // ---------- Custom order (per playlist) ----------

        /** The saved custom order of track ids for [playlistId], or empty if none. */
        fun customOrderFor(playlistId: String): List<Long> = customOrders[playlistId].orEmpty()

        /** Overwrite the saved custom order for [playlistId]. */
        fun setCustomOrder(
            playlistId: String,
            orderedTrackIds: List<Long>,
        ) {
            if (orderedTrackIds.isEmpty()) {
                customOrders.remove(
                    playlistId,
                )
            } else {
                customOrders[playlistId] = orderedTrackIds
            }
            scope.launch {
                if (orderedTrackIds.isEmpty()) {
                    customOrderDao.delete(playlistId)
                } else {
                    customOrderDao.upsert(CustomOrderEntity(playlistId, orderedTrackIds.joinToString(",")))
                }
            }
        }

        /**
         * Move [trackId] one slot [up] within the saved custom order for [playlistId].
         * If no custom order exists yet, seeds it from [currentOrder] (the playlist's
         * current track id sequence) so the first reorder has something to work with.
         *
         * The saved order is also reconciled against [currentOrder] on every move:
         * ids no longer in the playlist are dropped, and ids that are in the playlist
         * but missing from the saved order (e.g. tracks added after the order was
         * first saved) are appended. Without this, moving one of those newer tracks
         * would silently no-op forever, since it could never be found in the stale
         * saved order — which is what made reordering appear to stop working once a
         * playlist grew past its original custom-order snapshot.
         */
        fun moveTrackInCustomOrder(
            playlistId: String,
            trackId: Long,
            up: Boolean,
            currentOrder: List<Long>,
        ) {
            val saved = customOrderFor(playlistId)
            val currentSet = currentOrder.toHashSet()
            val reconciled = saved.filter { it in currentSet } + currentOrder.filter { it !in saved }
            val base = reconciled.ifEmpty { currentOrder }
            val idx = base.indexOf(trackId)
            if (idx < 0) return
            val target = if (up) idx - 1 else idx + 1
            if (target < 0 || target >= base.size) return
            val swapped = base.toMutableList()
            val tmp = swapped[idx]
            swapped[idx] = swapped[target]
            swapped[target] = tmp
            setCustomOrder(playlistId, swapped)
        }

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
            var added = false
            _favoriteTrackIds.value =
                _favoriteTrackIds.value.toMutableSet().apply {
                    added = add(trackId)
                    if (!added) remove(trackId)
                }
            scope.launch {
                if (added) favoriteDao.add(FavoriteTrackEntity(trackId)) else favoriteDao.remove(trackId)
            }
            recomputeFavorites()
        }

        /** The always-present "Favorite tracks" playlist (kind = FAVORITE_TRACKS). */
        fun favoriteTracksPlaylist(): Playlist =
            Playlist(
                id = "native-favorite-tracks",
                name = "Favorite tracks",
                trackIds = _favoriteTrackIds.value.toList(),
                kind = PlaylistKind.FAVORITE_TRACKS,
            )

        // ---------- Play stats ----------

        /**
         * Record that [trackId] was just played: bumps its play count and stamps
         * "now" as its last-played time. Drives the "Recently played" and "Most
         * played" native playlists (see [nativePlaylists]). Called by
         * [com.simplesound.app.playback.PlaybackService] once a track has been
         * playing continuously for a few seconds, so a quick skip-through doesn't
         * count as a play.
         */
        @Synchronized
        fun recordTrackPlayed(trackId: Long) {
            if (_tracks.value.none { it.id == trackId }) return
            val prevCount = playStats[trackId]?.first ?: 0
            val nowSec = System.currentTimeMillis() / 1000
            val newCount = prevCount + 1
            playStats[trackId] = newCount to nowSec
            scope.launch { playStatsDao.upsert(PlayStatsEntity(trackId, newCount, nowSec)) }
            _tracks.value =
                _tracks.value.map {
                    if (it.id == trackId) it.copy(playCount = newCount, lastPlayedSec = nowSec) else it
                }
        }

        // ---------- Native (computed) playlists ----------

        fun nativePlaylists(): List<Playlist> {
            val all = _tracks.value
            val recentlyAdded = all.sortedByDescending { it.dateAddedSec }
            val mostPlayed = all.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
            val recentlyPlayed = all.filter { it.lastPlayedSec > 0 }.sortedByDescending { it.lastPlayedSec }
            return listOf(
                Playlist(
                    "native-recently-added",
                    "Recently added",
                    recentlyAdded.take(NATIVE_LIMIT).map { it.id },
                    kind = PlaylistKind.RECENTLY_ADDED,
                ),
                Playlist(
                    "native-most-played",
                    "Most played",
                    mostPlayed.take(NATIVE_LIMIT).map { it.id },
                    kind = PlaylistKind.MOST_PLAYED,
                ),
                Playlist(
                    "native-recently-played",
                    "Recently played",
                    recentlyPlayed.take(NATIVE_LIMIT).map { it.id },
                    kind = PlaylistKind.RECENTLY_PLAYED,
                ),
                favoriteTracksPlaylist(),
            )
        }

        // ---------- Favorites tab contents ----------
        // (_favoritesTab/favoritesTabPlaylists are declared earlier, before `init`.)

        private fun recomputeFavorites() {
            _favoritesTab.value = computeFavoritesTab()
        }

        private fun computeFavoritesTab(): List<Playlist> {
            val hearted =
                _userPlaylists.value
                    .filter { it.favorited }
                    .sortedByDescending { it.favoritedAt }
            return listOf(favoriteTracksPlaylist()) + hearted
        }

        // ---------- Playlist mutations ----------

        fun createPlaylist(
            name: String,
            trackIds: List<Long> = emptyList(),
        ): String {
            val id = "user-" + UUID.randomUUID().toString()
            val position = _userPlaylists.value.size
            val playlist = Playlist(id, name.ifBlank { "New playlist" }, trackIds)
            _userPlaylists.value = _userPlaylists.value + playlist
            scope.launch {
                playlistDao.insert(playlist.toEntity(position))
                if (trackIds.isNotEmpty()) playlistTrackDao.replaceForPlaylist(id, trackIds)
            }
            return id
        }

        fun renamePlaylist(
            id: String,
            name: String,
        ) = update(id) { it.copy(name = name.ifBlank { it.name }) }

        fun setPlaylistCover(
            id: String,
            coverUri: String?,
        ) = update(id) { it.copy(coverUri = coverUri) }

        fun addTracksToPlaylist(
            id: String,
            trackIds: List<Long>,
        ) = updateTracks(id) {
            it.copy(trackIds = (it.trackIds + trackIds).distinct())
        }

        fun removeTrackFromPlaylist(
            id: String,
            trackId: Long,
        ) = updateTracks(id) {
            it.copy(trackIds = it.trackIds - trackId)
        }

        /** Remove several tracks at once from a single playlist. */
        fun removeTracksFromPlaylist(
            id: String,
            trackIds: List<Long>,
        ) = updateTracks(id) {
            val ids = trackIds.toSet()
            it.copy(trackIds = it.trackIds.filterNot { id -> id in ids })
        }

        fun toggleFavoritePlaylist(id: String) {
            update(id) { pl ->
                val newFavorited = !pl.favorited
                pl.copy(
                    favorited = newFavorited,
                    favoritedAt = if (newFavorited) System.currentTimeMillis() else 0L,
                )
            }
        }

        fun deletePlaylist(id: String) {
            // Native (default) playlists are computed and always present; they can never
            // be deleted. They are not stored in _userPlaylists, so this guard is a no-op
            // in normal flow but makes the contract explicit.
            if (id.startsWith("native-")) return
            _userPlaylists.value = _userPlaylists.value.filterNot { it.id == id }
            scope.launch {
                playlistDao.deleteById(id)
                playlistTrackDao.deleteForPlaylist(id)
                customOrderDao.delete(id)
            }
            recomputeFavorites()
        }

        /** Permanently remove a track from the library, all playlists, and favorites. */
        fun deleteTrack(trackId: Long) = deleteTracks(listOf(trackId))

        /** Permanently remove several tracks at once from the library, playlists, and favorites. */
        fun deleteTracks(trackIds: List<Long>) {
            if (trackIds.isEmpty()) return
            val ids = trackIds.toSet()
            _tracks.value = _tracks.value.filterNot { it.id in ids }
            _favoriteTrackIds.value = _favoriteTrackIds.value.filterNot { it in ids }.toSet()
            _userPlaylists.value =
                _userPlaylists.value.map { pl ->
                    pl.copy(trackIds = pl.trackIds.filterNot { it in ids })
                }
            scope.launch {
                trackDao.deleteByIds(trackIds)
                favoriteDao.removeAll(trackIds)
                playlistTrackDao.deleteByTrackIds(trackIds)
            }
            recomputeFavorites()
        }

        /** Persist a custom drag-reorder of the user playlists. */
        fun reorderPlaylists(orderedIds: List<String>) {
            val byId = _userPlaylists.value.associateBy { it.id }
            _userPlaylists.value = orderedIds.mapNotNull { byId[it] }
            scope.launch { orderedIds.forEachIndexed { index, id -> playlistDao.updatePosition(id, index) } }
        }

        fun playlistById(id: String): Playlist? =
            _userPlaylists.value.firstOrNull { it.id == id }
                ?: nativePlaylists().firstOrNull { it.id == id }
                ?: favoriteTracksPlaylist().takeIf { it.id == id }

        private inline fun update(
            id: String,
            transform: (Playlist) -> Playlist,
        ) {
            var updated: Playlist? = null
            _userPlaylists.value =
                _userPlaylists.value.map {
                    if (it.id == id) transform(it).also { p -> updated = p } else it
                }
            val position = _userPlaylists.value.indexOfFirst { it.id == id }
            updated?.let { p -> if (position >= 0) scope.launch { playlistDao.update(p.toEntity(position)) } }
            recomputeFavorites()
        }

        private inline fun updateTracks(
            id: String,
            transform: (Playlist) -> Playlist,
        ) {
            var newTrackIds: List<Long>? = null
            _userPlaylists.value =
                _userPlaylists.value.map {
                    if (it.id == id) transform(it).also { p -> newTrackIds = p.trackIds } else it
                }
            newTrackIds?.let { ids -> scope.launch { playlistTrackDao.replaceForPlaylist(id, ids) } }
        }

        // ---------- Legacy SharedPreferences decoding (migration only) ----------
        //
        // Compact, dependency-free string format the pre-Room repository used to
        // write. Only kept around long enough to read a pre-upgrade install's data
        // once in migrateLegacyPrefsIfNeeded() above; nothing writes this format
        // anymore. Record separator = '\u0001', field separator = '\u0002'.

        private fun decodeLegacyPlaylists(raw: String?): List<Playlist>? {
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
                    favoritedAt = favoritedAt,
                )
            }
        }

        private fun decodeLegacyFavoriteTrackIds(raw: String?): Set<Long>? {
            if (raw == null) return null
            if (raw.isEmpty()) return emptySet()
            return raw.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        }

        private fun decodeLegacyCustomOrders(raw: String?): Map<String, List<Long>> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split("\u0001").mapNotNull { record ->
                val parts = record.split("\u0002")
                if (parts.size < 2) return@mapNotNull null
                val id = parts[0]
                val ids = parts[1].split(",").mapNotNull { it.toLongOrNull() }
                id to ids
            }.toMap()
        }

        private fun decodeLegacyPlayStats(raw: String?): Map<Long, Pair<Int, Long>> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split("\u0001").mapNotNull { record ->
                val f = record.split("\u0002")
                if (f.size < 3) return@mapNotNull null
                val id = f[0].toLongOrNull() ?: return@mapNotNull null
                val count = f[1].toIntOrNull() ?: return@mapNotNull null
                val lastPlayedSec = f[2].toLongOrNull() ?: return@mapNotNull null
                id to (count to lastPlayedSec)
            }.toMap()
        }
    }
