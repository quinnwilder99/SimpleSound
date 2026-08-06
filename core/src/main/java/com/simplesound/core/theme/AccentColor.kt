package com.simplesound.core.theme

/**
 * User-selectable accent palette identifier. Pure enum (no Compose dependency) so it
 * can live in :core and be referenced by :data without pulling in the UI layer.
 *
 * The actual [androidx.compose.ui.graphics.Color] each value maps to is resolved in
 * the :ui module via [com.simplesound.app.ui.theme.color].
 *
 * The palette includes both vivid tints and neutral solids (black, grey, white) so
 * the user can dial the accent all the way down to a monochrome look. The default
 * [GradientGrey] resolves to a soft grey-to-graphite gradient in the :ui layer.
 */
enum class AccentColor {
    GradientGrey,
    Black,
    Graphite,
    Silver,
    White,
    Teal,
    Violet,
    Coral,
    Amber,
    Rose,
    Lime,
    Sky,
    Sand;

    companion object {
        val Default = GradientGrey
        fun fromName(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: Default
    }
}