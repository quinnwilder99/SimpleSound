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
 * [Graphite] is a soft solid graphite — a neutral, muted starting point that
 * keeps the dark UI quiet by default. ([GradientGrey] remains available as a
 * grey-to-graphite gradient option in the picker.)
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
    Sand,
    ;

    companion object {
        val Default = Graphite

        fun fromName(name: String?): AccentColor = entries.firstOrNull { it.name == name } ?: Default
    }
}
