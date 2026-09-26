package com.simplesound.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.simplesound.app.data.model.Track

/**
 * Builds the [MediaItem] fed to the player/[androidx.media3.session.MediaSession]. The
 * track's own content URI is stashed in [MediaMetadata.extras] so
 * [TrackArtworkBitmapLoader] can decode its *embedded* per-track picture for the
 * lock-screen/notification widget; [Track.albumArtUri] is passed as
 * [MediaMetadata.artworkUri] only as the album-level fallback when no embedded
 * picture exists (see MediaStoreScanner's "Artwork strategy" doc comment and
 * ui/Artwork.kt, which follow the same order).
 */
internal fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply { putString(TrackArtworkBitmapLoader.KEY_TRACK_CONTENT_URI, uri) }
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri.ifBlank { Uri.EMPTY.toString() })
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistOrUnknown)
                .setAlbumTitle(albumOrUnknown)
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setExtras(extras)
                .apply { albumArtUri?.let { setArtworkUri(Uri.parse(it)) } }
                .build(),
        )
        .build()
}

/**
 * Best-effort [Track] rebuilt from a [MediaItem]'s own metadata, for when the library
 * lookup can't resolve its id (e.g. the library is still loading). Enough for the
 * mini player / Now Playing header to render.
 */
internal fun MediaItem.toSnapshotTrack(): Track? {
    val id = mediaId.toLongOrNull() ?: return null
    val md = mediaMetadata
    return Track(
        id = id,
        title = md.title?.toString().orEmpty(),
        artist = md.artist?.toString().orEmpty(),
        album = md.albumTitle?.toString().orEmpty(),
        durationMs = md.durationMs ?: 0L,
        uri =
            md.extras?.getString(TrackArtworkBitmapLoader.KEY_TRACK_CONTENT_URI)
                ?: localConfiguration?.uri?.toString().orEmpty(),
        albumArtUri = md.artworkUri?.toString(),
    )
}

/** The player's media item indices in the order they will actually play (honours shuffle). */
internal fun Player.playOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val shuffle = shuffleModeEnabled
    val order = ArrayList<Int>(timeline.windowCount)
    var index = timeline.getFirstWindowIndex(shuffle)
    while (index != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return order
}
