package com.simplesound.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplesound.app.data.DEFAULT_PLAYLIST_SORT
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Tab
import com.simplesound.app.data.model.TabSetting
import com.simplesound.app.data.model.Track
import com.simplesound.core.theme.AccentColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-wide ViewModel. Bridges the [SettingsStore] (accent, tab config) and the
 * [MusicRepository] (library, playlists, favorites) to the Compose UI, and exposes
 * the small set of mutations wired up in v0.1.
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val repository: MusicRepository,
    ) : ViewModel() {
        val accent =
            settings.accent
                .stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.Default)

        val tabSettings =
            settings.tabSettings
                .stateIn(viewModelScope, SharingStarted.Eagerly, Tab.Default.map { TabSetting(it, true) })

        /** The user's last-chosen sort order on the Tracks tab (persisted). */
        val tracksSort =
            settings.tracksSort
                .stateIn(viewModelScope, SharingStarted.Eagerly, SortOption.DATE_ADDED)

        /** Crossfade duration in seconds (0 = off); the fade itself runs in PlaybackService. */
        val crossfadeSeconds =
            settings.crossfadeSeconds
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        // Repository-backed library flows.
        val tracks = repository.tracks
        val userPlaylists = repository.userPlaylists
        val favoriteTrackIds = repository.favoriteTrackIds
        val favoritesTabPlaylists = repository.favoritesTabPlaylists

        /**
         * Temporarily hide the global persistent mini player. Set to `true` by screens
         * that present their own bottom-aligned overlay UI (e.g. multi-selection action
         * bars or modal dialogs) so the mini player can't intercept touches on top of
         * them. Any screen that turns this on MUST turn it back off when its overlay
         * disappears (and on exit) so the mini player is restored for the rest of the app.
         */
        private val _miniPlayerHidden = MutableStateFlow(false)
        val miniPlayerHidden: StateFlow<Boolean> = _miniPlayerHidden.asStateFlow()

        fun setMiniPlayerHidden(hidden: Boolean) {
            _miniPlayerHidden.value = hidden
        }

        fun loadDeviceLibrary(context: Context) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.loadDeviceLibrary(context)
            }
        }

        // ---- Settings mutations ----
        fun setAccent(accent: AccentColor) = viewModelScope.launch { settings.setAccent(accent) }

        fun setTabSettings(list: List<TabSetting>) =
            viewModelScope.launch {
                // Tracks can never be disabled.
                val safe = list.map { if (it.tab.isMandatory) it.copy(enabled = true) else it }
                settings.setTabSettings(safe)
            }

        fun setTracksSort(option: SortOption) = viewModelScope.launch { settings.setTracksSort(option) }

        fun setCrossfadeSeconds(seconds: Int) = viewModelScope.launch { settings.setCrossfadeSeconds(seconds) }

        /**
         * In-memory cache of each playlist's last-known sort option. Seeding new
         * [playlistSort] StateFlows from here (instead of an unconditional
         * [DEFAULT_PLAYLIST_SORT]) avoids a one-frame flash of the default sort
         * when a playlist was last sorted by Custom order — which previously made
         * the list visibly jump whenever the detail screen was reopened.
         */
        private val playlistSortCache = mutableMapOf<String, SortOption>()

        /**
         * The user's last-chosen sort for a specific playlist (persisted per playlist).
         * The returned [StateFlow] is seeded from [playlistSortCache] when a cached
         * value exists, falling back to [default] only on the very first open. The
         * cache is kept in sync by [setPlaylistSort] and by collection of the
         * underlying persisted flow, so subsequent recompositions reuse the
         * last-applied sort instead of resetting to the default.
         *
         * [default] lets a computed playlist (e.g. "Most played"/"Recently played")
         * open sorted by its natural, already-meaningful order ([SortOption.CUSTOM_ORDER],
         * which [MusicRepository.sortPlaylistTracks] passes through unchanged when no
         * custom order has been saved) instead of [DEFAULT_PLAYLIST_SORT] — otherwise
         * "Most played" would render sorted by date-added on first open, silently
         * discarding the play-count order it exists to show.
         *
         * Each call launches a new eager collector in [viewModelScope] that lives
         * for the ViewModel's lifetime, so callers MUST `remember(playlistId)` the
         * returned flow rather than invoking this directly in a composable body —
         * otherwise every recomposition leaks another collector.
         */
        fun playlistSort(
            playlistId: String,
            default: SortOption = DEFAULT_PLAYLIST_SORT,
        ): StateFlow<SortOption> {
            val seed = playlistSortCache[playlistId] ?: default
            return settings.playlistSort(playlistId, default)
                .onEach { playlistSortCache[playlistId] = it }
                .stateIn(viewModelScope, SharingStarted.Eagerly, seed)
        }

        fun setPlaylistSort(
            playlistId: String,
            option: SortOption,
        ) = viewModelScope.launch {
            playlistSortCache[playlistId] = option
            settings.setPlaylistSort(playlistId, option)
        }

        // ---- Library / playlist passthroughs ----
        fun sortedTracks(option: SortOption) = repository.sortedTracks(option)

        fun sortTracks(
            tracks: List<Track>,
            option: SortOption,
        ) = repository.sortTracks(tracks, option)

        fun sortPlaylistTracks(
            playlistId: String,
            tracks: List<Track>,
            option: SortOption,
        ) = repository.sortPlaylistTracks(playlistId, tracks, option)

        fun moveTrackInCustomOrder(
            playlistId: String,
            trackId: Long,
            up: Boolean,
            currentOrder: List<Long>,
        ) = repository.moveTrackInCustomOrder(playlistId, trackId, up, currentOrder)

        fun searchTracks(query: String) = repository.searchTracks(query)

        fun isFavorite(trackId: Long) = repository.isFavorite(trackId)

        fun toggleFavoriteTrack(trackId: Long) = repository.toggleFavoriteTrack(trackId)

        fun toggleFavoritePlaylist(id: String) = repository.toggleFavoritePlaylist(id)

        fun createPlaylist(
            name: String,
            trackIds: List<Long> = emptyList(),
        ) = repository.createPlaylist(name, trackIds)

        fun renamePlaylist(
            id: String,
            name: String,
        ) = repository.renamePlaylist(id, name)

        fun setPlaylistCover(
            id: String,
            uri: String?,
        ) = repository.setPlaylistCover(id, uri)

        fun addTracksToPlaylist(
            id: String,
            ids: List<Long>,
        ) = repository.addTracksToPlaylist(id, ids)

        fun removeTracksFromPlaylist(
            id: String,
            ids: List<Long>,
        ) = repository.removeTracksFromPlaylist(id, ids)

        fun deletePlaylist(id: String) = repository.deletePlaylist(id)

        fun deleteTrack(trackId: Long) = repository.deleteTrack(trackId)

        fun deleteTracks(trackIds: List<Long>) = repository.deleteTracks(trackIds)

        fun reorderPlaylists(orderedIds: List<String>) = repository.reorderPlaylists(orderedIds)

        fun nativePlaylists() = repository.nativePlaylists()

        fun playlistById(id: String) = repository.playlistById(id)

        fun tracksByIds(ids: List<Long>) = repository.tracksByIds(ids)
    }
