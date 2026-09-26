package com.simplesound.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.simplesound.app.data.db.AppDatabase
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [playlistById], and the UI never waits on a disk round-trip to react) and queues
 * the matching Room write. Room is therefore the durable, queryable, indexed store;
 * the in-memory flows are a write-through cache that gives the UI instant,
 * always-consistent reads.
 *
 * Concurrency rules:
 * - All in-memory state is read-modify-written only while holding [lock]: mutations
 *   arrive from the main thread (UI), the playback service, and IO threads (library
 *   sync), and an unguarded `value = value.map { ... }` would drop concurrent updates.
 * - Room writes go through a single FIFO [writeQueue] consumed by one coroutine, so
 *   they land in exactly the order the in-memory mutations happened. (Launching each
 *   write independently on Dispatchers.IO let e.g. a quick favorite/unfavorite reach
 *   the database in the opposite order, leaving Room disagreeing with the UI until
 *   the next restart revealed it.)
 *
 * Tracks that disappear from MediaStore are kept (soft-missing) for a grace period
 * rather than having their favorites/playlist slots/stats deleted — see
 * [reconcileLibrary]. The internal playlist/favorite state keeps their ids so they
 * come back intact; the public flows hide them while they are missing.
 */
@Singleton
class MusicRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: AppDatabase,
        private val trackDao: TrackDao,
        private val playlistDao: PlaylistDao,
        private val playlistTrackDao: PlaylistTrackDao,
        private val favoriteDao: FavoriteDao,
        private val playStatsDao: PlayStatsDao,
        private val customOrderDao: CustomOrderDao,
    ) {
        private companion object {
            const val TAG = "MusicRepository"
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

            /**
             * Max ids bound into one `IN (...)` statement. SQLite before 3.32
             * (Android 8–11) rejects statements with more than 999 bound variables, so
             * any bulk delete (e.g. "select all" -> delete) is split into chunks.
             */
            const val IN_CHUNK = 500
        }

        /** Background scope for Room work. Failures are logged by the consumers below, never thrown. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** FIFO of pending Room writes; see the class doc's concurrency rules. */
        private val writeQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        /** Serializes whole library syncs (the ViewModel and the sync worker can both trigger one). */
        private val syncMutex = Mutex()

        /** Guards every in-memory field below. */
        private val lock = Any()

        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private val _tracks = MutableStateFlow<List<Track>>(emptyList())

        /** Tracks currently on the device (never includes soft-missing ones). */
        val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

        private var tracksById: Map<Long, Track> = emptyMap()

        /** Ids of tracks gone from MediaStore but still inside their grace period. */
        private var missingIds: Set<Long> = emptySet()

        /** Set once a MediaStore scan has populated [_tracks]; stops the slower Room read-back from overwriting it. */
        private var scanApplied = false

        private val libraryLoaded = CompletableDeferred<Unit>()

        /**
         * Per-track play stats (playCount, lastPlayedSec), keyed by track id. Kept
         * separately from [_tracks] because the track list itself is rebuilt from
         * scratch on every MediaStore scan ([loadDeviceLibrary]) — without this
         * side table, every rescan would silently wipe out play counts.
         * Merged back onto freshly scanned tracks via [applyPlayStats].
         */
        private val playStats = HashMap<Long, Pair<Int, Long>>()

        /** In-memory mirror of [CustomOrderEntity] rows; see [customOrderFor]. */
        private val customOrders = HashMap<String, List<Long>>()

        /** User playlists with their FULL membership, including soft-missing tracks. */
        private var allPlaylists: List<Playlist> = emptyList()

        /** Hearted track ids, including soft-missing tracks. */
        private var allFavorites: Set<Long> = emptySet()

        private val _userPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
        val userPlaylists: StateFlow<List<Playlist>> = _userPlaylists.asStateFlow()

        private val _favoriteTrackIds = MutableStateFlow<Set<Long>>(emptySet())
        val favoriteTrackIds: StateFlow<Set<Long>> = _favoriteTrackIds.asStateFlow()

        private val _favoritesTab = MutableStateFlow<List<Playlist>>(emptyList())
        val favoritesTabPlaylists: StateFlow<List<Playlist>> = _favoritesTab.asStateFlow()

        init {
            scope.launch {
                for (write in writeQueue) {
                    try {
                        write()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") t: Throwable,
                    ) {
                        // The in-memory state already reflects the change; losing only
                        // the durable copy is better than crashing the process, but it
                        // must not be invisible either.
                        Log.e(TAG, "Room write failed", t)
                    }
                }
            }
            // Blocking is deliberate: SimpleSoundApp field-injects this repository,
            // which Hilt constructs before Application.onCreate()'s body runs, so
            // playlists/favorites are guaranteed ready before any screen reads them.
            // Only the small tables are read here -- the (potentially large) track
            // table is loaded in the background below so launch time doesn't grow
            // with the library.
            runBlocking(Dispatchers.IO) {
                migrateLegacyPrefsIfNeeded()
                restoreFromDatabase()
            }
            scope.launch { restoreTracksFromDatabase() }
        }

        /** Suspends until the track library has been loaded (from Room or a scan). */
        suspend fun awaitLibraryLoaded() = libraryLoaded.await()

        /**
         * Waits for every write queued so far to finish. Not needed by production code
         * (writes are fire-and-forget by design) — used by tests that assert against
         * Room after calling a mutator.
         */
        internal suspend fun awaitPendingWrites() = writeAndAwait { }

        private fun write(block: suspend () -> Unit) {
            writeQueue.trySend(block)
        }

        /** Queues [block] behind every pending write and suspends until it has run. */
        private suspend fun <T> writeAndAwait(block: suspend () -> T): T {
            val result = CompletableDeferred<T>()
            writeQueue.send { result.completeWith(runCatching { block() }) }
            return result.await()
        }

        // ---------- Init / persistence ----------

        private suspend fun restoreFromDatabase() {
            val stats = playStatsDao.getAll()
            val favorites = favoriteDao.getAll().toSet()
            val missing = trackDao.getMissingIds().toSet()
            val crossRefsByPlaylist = playlistTrackDao.getAll().groupBy { it.playlistId }
            val playlists =
                playlistDao.getAll().sortedBy { it.position }.map { pe ->
                    pe.toDomain(crossRefsByPlaylist[pe.id].orEmpty().sortedBy { it.position }.map { it.trackId })
                }
            val orders =
                customOrderDao.getAll().associate { it.playlistId to parseIdCsv(it.orderedTrackIdsCsv) }

            synchronized(lock) {
                stats.forEach { playStats[it.trackId] = it.playCount to it.lastPlayedSec }
                customOrders.putAll(orders)
                missingIds = missing
                allFavorites = favorites
                allPlaylists = playlists
                publishLocked()
            }
        }

        private suspend fun restoreTracksFromDatabase() {
            val stored = runCatching { trackDao.getPresent().map { it.toDomain() } }.getOrElse { emptyList() }
            synchronized(lock) {
                if (!scanApplied) setTracksLocked(stored)
            }
            libraryLoaded.complete(Unit)
        }

        /** Must hold [lock]. */
        private fun setTracksLocked(list: List<Track>) {
            val withStats = applyPlayStats(list)
            tracksById = withStats.associateBy { it.id }
            _tracks.value = withStats
        }

        /**
         * Re-derives every public flow from the internal state, hiding soft-missing
         * tracks. Must hold [lock].
         */
        private fun publishLocked() {
            val missing = missingIds
            _userPlaylists.value =
                if (missing.isEmpty()) {
                    allPlaylists
                } else {
                    allPlaylists.map { pl -> pl.copy(trackIds = pl.trackIds.filterNot { it in missing }) }
                }
            _favoriteTrackIds.value = if (missing.isEmpty()) allFavorites else allFavorites - missing
            _favoritesTab.value = computeFavoritesTab()
        }

        /**
         * One-time migration from the pre-Room SharedPreferences blob into the Room
         * tables. No-ops on every launch after the first once [KEY_ROOM_MIGRATED] is
         * set, and no-ops entirely on a fresh install (nothing to migrate).
         */
        private suspend fun migrateLegacyPrefsIfNeeded() {
            if (prefs.getBoolean(KEY_ROOM_MIGRATED, false)) return
            // This is a one-time bootstrap step that runs inside runBlocking() in
            // init{} — an uncaught exception here would crash Application
            // construction itself. try/finally guarantees KEY_ROOM_MIGRATED is set
            // even if a step fails partway, so a single bad launch can't turn into a
            // permanent boot loop that only clearing app data would escape. The
            // per-row DAO calls below (REPLACE/IGNORE/upsert) are also idempotent, so
            // a retry of a partially-completed run can't throw a constraint error.
            try {
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
            } finally {
                prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
            }
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

        /**
         * Rescans MediaStore and merges the result into Room and memory. Runs on every
         * app open and whenever [com.simplesound.app.data.sync.MediaStoreObserver]
         * sees the device's audio change.
         *
         * A track missing from the scan is kept as soft-missing (see
         * [reconcileLibrary]) rather than having its favorites/playlists/stats deleted,
         * so an unmounted SD card or a MediaStore rebuild can't cost the user data.
         */
        suspend fun loadDeviceLibrary(context: Context) {
            // Without read access MediaStore returns an empty result instead of
            // throwing, which would otherwise read as "every track was removed".
            if (!hasAudioPermission(context)) {
                libraryLoaded.complete(Unit)
                return
            }
            val scanned = runCatching { MediaStoreScanner.scan(context) }.getOrNull()
            if (scanned == null) {
                libraryLoaded.complete(Unit)
                return
            }
            syncScannedLibrary(scanned)
        }

        /** Merges an already-complete MediaStore snapshot; split out of [loadDeviceLibrary] for tests. */
        internal suspend fun syncScannedLibrary(scanned: List<Track>) {
            syncMutex.withLock {
                val result =
                    runCatching { writeAndAwait { persistScan(scanned) } }
                        .onFailure { Log.e(TAG, "Library sync failed", it) }
                        .getOrNull()
                if (result != null) applyScanInMemory(scanned, result)
                libraryLoaded.complete(Unit)
            }
        }

        /** Runs on the write queue so it is ordered with every other Room write. */
        private suspend fun persistScan(scanned: List<Track>): ReconcileResult =
            db.withTransaction {
                val reconciled =
                    reconcileLibrary(
                        existing = trackDao.getAll(),
                        scanned = scanned.map { it.toEntity() },
                        nowSec = System.currentTimeMillis() / 1000,
                    )
                trackDao.replaceAll(reconciled.tracks)
                if (reconciled.changesReferences) rewriteReferences(reconciled)
                favoriteDao.purgeOrphans()
                playStatsDao.purgeOrphans()
                playlistTrackDao.purgeOrphans()
                reconciled
            }

        /** Moves/drops every stored reference per [result]. Only runs when ids actually changed (rare). */
        private suspend fun rewriteReferences(result: ReconcileResult) {
            val favorites = result.remapIds(favoriteDao.getAll())
            favoriteDao.deleteAll()
            favoriteDao.addAll(favorites.map { FavoriteTrackEntity(it) })

            val stats =
                remapStats(playStatsDao.getAll().associate { it.trackId to (it.playCount to it.lastPlayedSec) }, result)
            playStatsDao.deleteAll()
            playStatsDao.upsertAll(stats.map { (id, s) -> PlayStatsEntity(id, s.first, s.second) })

            playlistTrackDao.getAll().groupBy { it.playlistId }.forEach { (playlistId, refs) ->
                val old = refs.sortedBy { it.position }.map { it.trackId }
                val new = result.remapIds(old)
                if (new != old) playlistTrackDao.replaceForPlaylist(playlistId, new)
            }

            customOrderDao.getAll().forEach { entity ->
                val old = parseIdCsv(entity.orderedTrackIdsCsv)
                val new = result.remapIds(old)
                if (new != old) customOrderDao.upsert(entity.copy(orderedTrackIdsCsv = new.joinToString(",")))
            }
        }

        private fun remapStats(
            stats: Map<Long, Pair<Int, Long>>,
            result: ReconcileResult,
        ): Map<Long, Pair<Int, Long>> {
            val out = HashMap<Long, Pair<Int, Long>>()
            for ((id, stat) in stats) {
                val target = result.resolve(id) ?: continue
                // Two old rows can collapse onto one new id; keep the combined history.
                out[target] = out[target]?.let { (it.first + stat.first) to maxOf(it.second, stat.second) } ?: stat
            }
            return out
        }

        private fun applyScanInMemory(
            scanned: List<Track>,
            result: ReconcileResult,
        ) {
            synchronized(lock) {
                if (result.changesReferences) {
                    val stats = remapStats(HashMap(playStats), result)
                    playStats.clear()
                    playStats.putAll(stats)
                    customOrders.replaceAll { _, ids -> result.remapIds(ids) }
                    allFavorites = result.remapIds(allFavorites.toList()).toSet()
                    allPlaylists = allPlaylists.map { it.copy(trackIds = result.remapIds(it.trackIds)) }
                }
                missingIds = result.missingIds
                scanApplied = true
                setTracksLocked(scanned)
                publishLocked()
            }
        }

        /** Overlay persisted [playStats] onto a freshly scanned track list. Must hold [lock]. */
        private fun applyPlayStats(tracks: List<Track>): List<Track> {
            if (playStats.isEmpty()) return tracks
            return tracks.map { track ->
                val stats = playStats[track.id] ?: return@map track
                track.copy(playCount = stats.first, lastPlayedSec = stats.second)
            }
        }

        fun trackById(id: Long): Track? = synchronized(lock) { tracksById[id] }

        fun tracksByIds(ids: List<Long>): List<Track> {
            val map = synchronized(lock) { tracksById }
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
        fun customOrderFor(playlistId: String): List<Long> = synchronized(lock) { customOrders[playlistId].orEmpty() }

        /** Overwrite the saved custom order for [playlistId]. */
        fun setCustomOrder(
            playlistId: String,
            orderedTrackIds: List<Long>,
        ) {
            val ids = orderedTrackIds.toList()
            synchronized(lock) {
                if (ids.isEmpty()) customOrders.remove(playlistId) else customOrders[playlistId] = ids
            }
            write {
                if (ids.isEmpty()) {
                    customOrderDao.delete(playlistId)
                } else {
                    customOrderDao.upsert(CustomOrderEntity(playlistId, ids.joinToString(",")))
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
            return _tracks.value.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true) ||
                    it.album.contains(q, ignoreCase = true)
            }
        }

        // ---------- Favorites (single tracks) ----------

        fun isFavorite(trackId: Long): Boolean = trackId in _favoriteTrackIds.value

        fun toggleFavoriteTrack(trackId: Long) {
            val added: Boolean
            synchronized(lock) {
                added = trackId !in allFavorites
                allFavorites = if (added) allFavorites + trackId else allFavorites - trackId
                publishLocked()
            }
            write { if (added) favoriteDao.add(FavoriteTrackEntity(trackId)) else favoriteDao.remove(trackId) }
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
         * genuinely listened to, so a quick skip-through doesn't count as a play.
         */
        fun recordTrackPlayed(trackId: Long) {
            val nowSec = System.currentTimeMillis() / 1000
            val newCount: Int
            synchronized(lock) {
                if (trackId !in tracksById) return
                newCount = (playStats[trackId]?.first ?: 0) + 1
                playStats[trackId] = newCount to nowSec
                setTracksLocked(
                    _tracks.value.map {
                        if (it.id == trackId) it.copy(playCount = newCount, lastPlayedSec = nowSec) else it
                    },
                )
            }
            write { playStatsDao.upsert(PlayStatsEntity(trackId, newCount, nowSec)) }
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

        /** Must hold [lock]; reads the already-published [_userPlaylists]/[_favoriteTrackIds]. */
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
            val ids = trackIds.distinct()
            val playlist = Playlist(id, name.ifBlank { "New playlist" }, ids)
            val position: Int
            synchronized(lock) {
                position = allPlaylists.size
                allPlaylists = allPlaylists + playlist
                publishLocked()
            }
            write {
                playlistDao.insert(playlist.toEntity(position))
                if (ids.isNotEmpty()) playlistTrackDao.replaceForPlaylist(id, ids)
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
            // be deleted. They are not stored in allPlaylists, so this guard is a no-op
            // in normal flow but makes the contract explicit.
            if (id.startsWith("native-")) return
            synchronized(lock) {
                allPlaylists = allPlaylists.filterNot { it.id == id }
                customOrders.remove(id)
                publishLocked()
            }
            write {
                db.withTransaction {
                    playlistDao.deleteById(id)
                    playlistTrackDao.deleteForPlaylist(id)
                    customOrderDao.delete(id)
                }
            }
        }

        /**
         * Remove tracks from the library, every playlist, favorites and play stats.
         * Called after the underlying files were actually deleted from the device (see
         * the UI's TrackDeleter) — removing only the database rows would just have the
         * next MediaStore scan bring the file back, minus its playlists.
         */
        fun deleteTrack(trackId: Long) = deleteTracks(listOf(trackId))

        /** See [deleteTrack]. */
        fun deleteTracks(trackIds: List<Long>) {
            if (trackIds.isEmpty()) return
            val ids = trackIds.toSet()
            synchronized(lock) {
                setTracksLocked(_tracks.value.filterNot { it.id in ids })
                missingIds = missingIds - ids
                allFavorites = allFavorites - ids
                allPlaylists = allPlaylists.map { pl -> pl.copy(trackIds = pl.trackIds.filterNot { it in ids }) }
                ids.forEach { playStats.remove(it) }
                publishLocked()
            }
            write {
                // One Room transaction, not independent writes -- process death between
                // them used to be able to leave orphaned rows. Chunked because a
                // "select all -> delete" can exceed SQLite's bound-variable limit.
                db.withTransaction {
                    ids.toList().chunked(IN_CHUNK).forEach { chunk ->
                        trackDao.deleteByIds(chunk)
                        favoriteDao.removeAll(chunk)
                        playlistTrackDao.deleteByTrackIds(chunk)
                        playStatsDao.removeAll(chunk)
                    }
                }
            }
        }

        /** Persist a custom drag-reorder of the user playlists. */
        fun reorderPlaylists(orderedIds: List<String>) {
            synchronized(lock) {
                val byId = allPlaylists.associateBy { it.id }
                // Keep any playlist the caller didn't list (e.g. created mid-drag) at the end.
                allPlaylists = orderedIds.mapNotNull { byId[it] } + allPlaylists.filterNot { it.id in orderedIds }
                publishLocked()
            }
            val order = orderedIds.toList()
            write {
                db.withTransaction { order.forEachIndexed { index, id -> playlistDao.updatePosition(id, index) } }
            }
        }

        fun playlistById(id: String): Playlist? =
            // nativePlaylists() already ends with favoriteTracksPlaylist(), so a third
            // fallback for it here would never be reached.
            _userPlaylists.value.firstOrNull { it.id == id }
                ?: nativePlaylists().firstOrNull { it.id == id }

        private inline fun update(
            id: String,
            transform: (Playlist) -> Playlist,
        ) {
            var updated: Playlist? = null
            var position = -1
            synchronized(lock) {
                allPlaylists =
                    allPlaylists.mapIndexed { index, pl ->
                        if (pl.id == id) {
                            position = index
                            transform(pl).also { updated = it }
                        } else {
                            pl
                        }
                    }
                publishLocked()
            }
            val p = updated ?: return
            val pos = position
            write { playlistDao.update(p.toEntity(pos)) }
        }

        private inline fun updateTracks(
            id: String,
            transform: (Playlist) -> Playlist,
        ) {
            var newTrackIds: List<Long>? = null
            synchronized(lock) {
                allPlaylists =
                    allPlaylists.map {
                        if (it.id == id) transform(it).also { p -> newTrackIds = p.trackIds } else it
                    }
                // Also refreshes a hearted playlist's snapshot in the Favorites tab.
                publishLocked()
            }
            val ids = newTrackIds ?: return
            write { playlistTrackDao.replaceForPlaylist(id, ids) }
        }

        private fun parseIdCsv(csv: String): List<Long> = csv.split(",").mapNotNull { it.toLongOrNull() }
    }
