package com.simplesound.app.data.model

/**
 * Which screen edge the lock-screen "edge control bar" (see Settings > Edge
 * control bar) docks to. [RIGHT] is the default.
 */
enum class EdgeBarSide {
    LEFT,
    RIGHT,
    ;

    companion object {
        val Default = RIGHT

        fun fromName(name: String?): EdgeBarSide = entries.firstOrNull { it.name == name } ?: Default
    }
}
