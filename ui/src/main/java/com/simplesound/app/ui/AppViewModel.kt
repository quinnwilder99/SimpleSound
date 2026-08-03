package com.simplesound.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Tab
import com.simplesound.app.data.model.TabSetting
import com.simplesound.app.data.model.Track
import com.simplesound.core.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide ViewModel. Bridges the [SettingsStore] (accent, tab config) and the
 * [MusicRepository] (library, playlists, favorites) to the Compose UI, and exposes
 * the small set of mutations wired up in v0.1.
 */
class AppViewModel(private val settings: SettingsStore) : ViewModel() {

    val accent = settings.accent
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.Default)

    val tabSettings = settings.tabSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Tab.Default.map { TabSetting(it, true) })

    /** The user's last-chosen sort order on the Tracks tab (persisted). */
    val tracksSort = settings.tracksSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortOption.DATE_ADDED)

    // Repository-backed library flows (shared singletons).
    val tracks = MusicRepository.tracks
    val userPlaylists = MusicRepository.userPlaylists
    val favoriteTrackIds = MusicRepository.favoriteTrackIds
    val favoritesTabPlaylists = MusicRepository.favoritesTabPlaylists

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
            MusicRepository.loadDeviceLibrary(context)
        }
    }

    // ---- Settings mutations ----
    fun setAccent(accent: AccentColor) = viewModelScope.launch { settings.setAccent(accent) }
    fun setTabSettings(list: List<TabSetting>) = viewModelScope.launch {
        // Tracks can never be disabled.
        val safe = list.map { if (it.tab.isMandatory) it.copy(enabled = true) else it }
        settings.setTabSettings(safe)
    }

    fun setTracksSort(option: SortOption) = viewModelScope.launch { settings.setTracksSort(option) }

    // ---- Library / playlist passthroughs ----
    fun sortedTracks(option: SortOption) = MusicRepository.sortedTracks(option)
    fun sortTracks(tracks: List<Track>, option: SortOption) = MusicRepository.sortTracks(tracks, option)
    fun searchTracks(query: String) = MusicRepository.searchTracks(query)
    fun isFavorite(trackId: Long) = MusicRepository.isFavorite(trackId)
    fun toggleFavoriteTrack(trackId: Long) = MusicRepository.toggleFavoriteTrack(trackId)
    fun toggleFavoritePlaylist(id: String) = MusicRepository.toggleFavoritePlaylist(id)
    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()) =
        MusicRepository.createPlaylist(name, trackIds)
    fun renamePlaylist(id: String, name: String) = MusicRepository.renamePlaylist(id, name)
    fun setPlaylistCover(id: String, uri: String?) = MusicRepository.setPlaylistCover(id, uri)
    fun addTracksToPlaylist(id: String, ids: List<Long>) = MusicRepository.addTracksToPlaylist(id, ids)
    fun removeTracksFromPlaylist(id: String, ids: List<Long>) = MusicRepository.removeTracksFromPlaylist(id, ids)
    fun deletePlaylist(id: String) = MusicRepository.deletePlaylist(id)
    fun deleteTrack(trackId: Long) = MusicRepository.deleteTrack(trackId)
    fun deleteTracks(trackIds: List<Long>) = MusicRepository.deleteTracks(trackIds)
    fun reorderPlaylists(orderedIds: List<String>) = MusicRepository.reorderPlaylists(orderedIds)
    fun nativePlaylists() = MusicRepository.nativePlaylists()
    fun playlistById(id: String) = MusicRepository.playlistById(id)
    fun tracksByIds(ids: List<Long>) = MusicRepository.tracksByIds(ids)

    class Factory(private val settings: SettingsStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(settings) as T
    }
}
