package com.simplesound.app.data.model

/** Sort orders offered on the Tracks tab. */
enum class SortOption(val label: String) {
    DATE_ADDED("Date added"),
    NAME("Name"),
    ARTIST("Artist"),
    LENGTH("Length");

    companion object {
        /** Resolves a stored sort name back to a [SortOption], defaulting to [DATE_ADDED]. */
        fun fromName(name: String?): SortOption =
            entries.firstOrNull { it.name == name } ?: DATE_ADDED
    }
}
