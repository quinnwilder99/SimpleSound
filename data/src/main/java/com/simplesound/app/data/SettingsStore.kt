package com.simplesound.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.simplesound.app.data.model.Tab
import com.simplesound.app.data.model.TabSetting
import com.simplesound.core.theme.AccentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "simplesound_settings")

/**
 * Persists the two "simple" settings that are live in v0.1: the accent color and
 * the tab configuration (order + enabled). Encoded compactly so no schema is
 * needed. Tracks is force-enabled on read regardless of what is stored.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ACCENT = stringPreferencesKey("accent")
        val TABS = stringPreferencesKey("tab_config")
    }

    val accent: Flow<AccentColor> = context.dataStore.data.map { prefs ->
        AccentColor.fromName(prefs[Keys.ACCENT])
    }

    val tabSettings: Flow<List<TabSetting>> = context.dataStore.data.map { prefs ->
        decodeTabs(prefs[Keys.TABS])
    }

    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit { it[Keys.ACCENT] = accent.name }
    }

    suspend fun setTabSettings(settings: List<TabSetting>) {
        context.dataStore.edit { it[Keys.TABS] = encodeTabs(settings) }
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
}
