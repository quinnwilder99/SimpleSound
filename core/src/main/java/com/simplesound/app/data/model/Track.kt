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
    /** Parent directory relative to its storage volume, e.g. "Music/Rap". */
    val folder: String = "",
    val dateAddedSec: Long = 0L,
    /**
     * Absolute file path as MediaStore reports it. Used only as a stable identity
     * across MediaStore id changes (see LibraryReconciler); may be blank.
     */
    val path: String = "",
    val playCount: Int = 0,
    val lastPlayedSec: Long = 0L,
) {
    val artistOrUnknown: String get() = artist.ifBlank { "<unknown>" }
    val albumOrUnknown: String get() = album.ifBlank { "<unknown>" }
}
