package com.simplesound.core.theme

/**
 * User-selectable accent palette identifier. Pure enum (no Compose dependency) so it
 * can live in :core and be referenced by :data without pulling in the UI layer.
 *
 * The actual [androidx.compose.ui.graphics.Color] each value maps to is resolved in
 * the :ui module via [com.simplesound.app.ui.theme.accentColorOf].
 */
enum class AccentColor {
    Teal,
    Violet,
    Coral,
    Amber,
    Rose,
    Lime,
    Sky,
    Sand;

    companion object {
        val Default = Teal
        fun fromName(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
