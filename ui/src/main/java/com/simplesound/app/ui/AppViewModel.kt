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
import com.simplesound.core.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
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

    // Repository-backed library flows (shared singletons).
    val tracks = MusicRepository.tracks
    val userPlaylists = MusicRepository.userPlaylists
    val favoriteTrackIds = MusicRepository.favoriteTrackIds
    val favoritesTabPlaylists = MusicRepository.favoritesTabPlaylists

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

    // ---- Library / playlist passthroughs ----
    fun sortedTracks(option: SortOption) = MusicRepository.sortedTracks(option)
    fun isFavorite(trackId: Long) = MusicRepository.isFavorite(trackId)
    fun toggleFavoriteTrack(trackId: Long) = MusicRepository.toggleFavoriteTrack(trackId)
    fun toggleFavoritePlaylist(id: String) = MusicRepository.toggleFavoritePlaylist(id)
    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()) =
        MusicRepository.createPlaylist(name, trackIds)
    fun renamePlaylist(id: String, name: String) = MusicRepository.renamePlaylist(id, name)
    fun setPlaylistCover(id: String, uri: String?) = MusicRepository.setPlaylistCover(id, uri)
    fun addTracksToPlaylist(id: String, ids: List<Long>) = MusicRepository.addTracksToPlaylist(id, ids)
    fun deletePlaylist(id: String) = MusicRepository.deletePlaylist(id)
    fun deleteTrack(trackId: Long) = MusicRepository.deleteTrack(trackId)
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
