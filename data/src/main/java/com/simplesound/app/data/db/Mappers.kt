package com.simplesound.app.data.db

import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.Track

fun Track.toEntity(): TrackEntity =
    TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = uri,
        albumArtUri = albumArtUri,
        folder = folder,
        dateAddedSec = dateAddedSec,
    )

/** Play count / last-played time are merged in separately from [PlayStatsEntity]. */
fun TrackEntity.toDomain(): Track =
    Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = uri,
        albumArtUri = albumArtUri,
        folder = folder,
        dateAddedSec = dateAddedSec,
    )

/** [Playlist.trackIds] is resolved separately from [PlaylistTrackCrossRef] rows. */
fun PlaylistEntity.toDomain(trackIds: List<Long>): Playlist =
    Playlist(
        id = id,
        name = name,
        trackIds = trackIds,
        coverUri = coverUri,
        favorited = favorited,
        favoritedAt = favoritedAt,
    )

fun Playlist.toEntity(position: Int): PlaylistEntity =
    PlaylistEntity(
        id = id,
        name = name,
        coverUri = coverUri,
        favorited = favorited,
        favoritedAt = favoritedAt,
        position = position,
    )
