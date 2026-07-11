package com.simplesound.app.data.model

/**
 * A single audio track. [uri] is a content:// (MediaStore) or file path string
 * that the player can resolve. Placeholder tracks use a synthetic id + empty uri.
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String? = null,
    val folder: String = "",
    val dateAddedSec: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedSec: Long = 0L
) {
    val artistOrUnknown: String get() = artist.ifBlank { "<unknown>" }
    val albumOrUnknown: String get() = album.ifBlank { "<unknown>" }
}
