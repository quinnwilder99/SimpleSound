package com.simplesound.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Tab
import com.simplesound.app.data.model.TabSetting
import com.simplesound.core.theme.AccentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "simplesound_settings")

/** Default sort applied to a playlist the user has never explicitly sorted. */
val DEFAULT_PLAYLIST_SORT: SortOption = SortOption.DATE_ADDED

/**
 * Persists the two "simple" settings that are live in v0.1: the accent color and
 * the tab configuration (order + enabled). Encoded compactly so no schema is
 * needed. Tracks is force-enabled on read regardless of what is stored.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ACCENT = stringPreferencesKey("accent")
        val TABS = stringPreferencesKey("tab_config")
        val TRACKS_SORT = stringPreferencesKey("tracks_sort")
        // Encoded "id1=NAME,id2=CUSTOM_ORDER,..." so each playlist remembers its
        // own last-chosen sort, independent of the Tracks tab sort.
        val PLAYLIST_SORTS = stringPreferencesKey("playlist_sorts")
    }

    val accent: Flow<AccentColor> = context.dataStore.data.map { prefs ->
        AccentColor.fromName(prefs[Keys.ACCENT])
    }

    val tabSettings: Flow<List<TabSetting>> = context.dataStore.data.map { prefs ->
        decodeTabs(prefs[Keys.TABS])
    }

    /** The user's last-chosen sort order on the Tracks tab. */
    val tracksSort: Flow<SortOption> = context.dataStore.data.map { prefs ->
        SortOption.fromName(prefs[Keys.TRACKS_SORT])
    }

    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit { it[Keys.ACCENT] = accent.name }
    }

    suspend fun setTabSettings(settings: List<TabSetting>) {
        context.dataStore.edit { it[Keys.TABS] = encodeTabs(settings) }
    }

    suspend fun setTracksSort(option: SortOption) {
        context.dataStore.edit { it[Keys.TRACKS_SORT] = option.name }
    }

    /** The user's last-chosen sort for [playlistId], or [DEFAULT_PLAYLIST_SORT]. */
    fun playlistSort(playlistId: String): Flow<SortOption> =
        context.dataStore.data.map { prefs ->
            decodePlaylistSorts(prefs[Keys.PLAYLIST_SORTS])[playlistId] ?: DEFAULT_PLAYLIST_SORT
        }

    /** Persist the chosen [option] for [playlistId]. */
    suspend fun setPlaylistSort(playlistId: String, option: SortOption) {
        context.dataStore.edit { prefs ->
            val current = decodePlaylistSorts(prefs[Keys.PLAYLIST_SORTS]).toMutableMap()
            current[playlistId] = option
            prefs[Keys.PLAYLIST_SORTS] = encodePlaylistSorts(current)
        }
    }

    // ---- encoding: "FAVORITES:1,TRACKS:1,PLAYLISTS:0,..." ----

    private fun encodeTabs(settings: List<TabSetting>): String =
        settings.joinToString(",") { "${it.tab.name}:${if (it.enabled) 1 else 0}" }

    private fun decodeTabs(raw: String?): List<TabSetting> {
        if (raw.isNullOrBlank()) return defaultTabs()
        val parsed = raw.split(",").mapNotNull { token ->
            val (name, flag) = token.split(":").let { it.getOrNull(0) to it.getOrNull(1) }
            val tab = name?.let { Tab.fromName(it) } ?: return@mapNotNull null
            TabSetting(tab, flag == "1")
        }
        // Make sure every known tab is present (handles app updates adding tabs)
        // and Tracks is always enabled.
        val known = parsed.map { it.tab }.toSet()
        val merged = parsed.toMutableList()
        Tab.entries.forEach { tab ->
            if (tab !in known) merged.add(TabSetting(tab, enabled = tab.isMandatory))
        }
        return merged.map { if (it.tab.isMandatory) it.copy(enabled = true) else it }
    }

    private fun defaultTabs(): List<TabSetting> =
        Tab.Default.map { TabSetting(it, enabled = true) }

    // ---- encodes a Map<playlistId, SortOption> as "id1=NAME,id2=CUSTOM_ORDER,..."
    private fun encodePlaylistSorts(map: Map<String, SortOption>): String =
        map.entries.joinToString(",") { (id, opt) ->
            // Guard against commas in ids by encoding the separator; ids are stable
            // machine strings (e.g. "user-...") so this is defensive only.
            "${id.replace(",", "\u0003")}=${opt.name}"
        }

    private fun decodePlaylistSorts(raw: String?): Map<String, SortOption> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val id = token.substring(0, eq).replace("\u0003", ",")
            val opt = SortOption.fromName(token.substring(eq + 1))
            id to opt
        }.toMap()
    }
}