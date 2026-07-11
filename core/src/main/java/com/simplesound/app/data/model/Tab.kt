package com.simplesound.app.data.model

/**
 * The tabs that can appear in the main navigation. Order and enabled-state are
 * user-configurable in Settings > Manage tabs, EXCEPT [TRACKS], which is always
 * enabled and cannot be turned off (it can still be reordered).
 */
enum class Tab(val label: String, val route: String) {
    FAVORITES("Favorites", "favorites"),
    TRACKS("Tracks", "tracks"),
    PLAYLISTS("Playlists", "playlists"),
    ALBUMS("Albums", "albums"),
    ARTISTS("Artists", "artists"),
    FOLDERS("Folders", "folders");

    /** Tracks is mandatory and cannot be disabled. */
    val isMandatory: Boolean get() = this == TRACKS

    companion object {
        val Default: List<Tab> = listOf(FAVORITES, TRACKS, PLAYLISTS, ALBUMS, ARTISTS, FOLDERS)
        fun fromName(name: String): Tab? = entries.firstOrNull { it.name == name }
    }
}

/** A tab together with its on/off state, in display order. */
data class TabSetting(val tab: Tab, val enabled: Boolean)
