package com.simplesound.app.data

import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind

// Decoders for the compact, dependency-free string format the pre-Room
// MusicRepository wrote to SharedPreferences. Only kept around long enough to read
// a pre-upgrade install's data once in MusicRepository.migrateLegacyPrefsIfNeeded();
// nothing writes this format anymore. Record separator = '\u0001', field separator = '\u0002'.

internal fun decodeLegacyPlaylists(raw: String?): List<Playlist>? {
    if (raw.isNullOrEmpty()) return null
    return raw.split("\u0001").mapNotNull { record ->
        val f = record.split("\u0002")
        if (f.size < 7) return@mapNotNull null
        val id = f[0]
        val kind = runCatching { PlaylistKind.valueOf(f[1]) }.getOrDefault(PlaylistKind.USER)
        val name = f[2]
        val cover = f[3].takeIf { it.isNotEmpty() }
        val favorited = f[4] == "1"
        val favoritedAt = f[5].toLongOrNull() ?: 0L
        val trackIds = f[6].split(",").mapNotNull { it.toLongOrNull() }
        Playlist(
            id = id,
            name = name,
            trackIds = trackIds,
            coverUri = cover,
            kind = kind,
            favorited = favorited,
            favoritedAt = favoritedAt,
        )
    }
}

internal fun decodeLegacyFavoriteTrackIds(raw: String?): Set<Long>? {
    if (raw == null) return null
    if (raw.isEmpty()) return emptySet()
    return raw.split(",").mapNotNull { it.toLongOrNull() }.toSet()
}

internal fun decodeLegacyCustomOrders(raw: String?): Map<String, List<Long>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split("\u0001").mapNotNull { record ->
        val parts = record.split("\u0002")
        if (parts.size < 2) return@mapNotNull null
        val id = parts[0]
        val ids = parts[1].split(",").mapNotNull { it.toLongOrNull() }
        id to ids
    }.toMap()
}

internal fun decodeLegacyPlayStats(raw: String?): Map<Long, Pair<Int, Long>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split("\u0001").mapNotNull { record ->
        val f = record.split("\u0002")
        if (f.size < 3) return@mapNotNull null
        val id = f[0].toLongOrNull() ?: return@mapNotNull null
        val count = f[1].toIntOrNull() ?: return@mapNotNull null
        val lastPlayedSec = f[2].toLongOrNull() ?: return@mapNotNull null
        id to (count to lastPlayedSec)
    }.toMap()
}
