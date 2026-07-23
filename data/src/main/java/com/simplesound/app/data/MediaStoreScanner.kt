package com.simplesound.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.simplesound.app.data.model.Track
import java.io.File

/**
 * Reads the device's audio library from MediaStore. Requires the READ_MEDIA_AUDIO
 * (API 33+) or READ_EXTERNAL_STORAGE permission to be granted; if not granted or
 * empty, callers fall back to [SampleData].
 *
 * ---- Artwork strategy ----
 *
 * `uri` = the track's own MediaStore content URI:
 *     content://media/external/audio/media/<trackId>
 * This is what the player uses for playback AND what the UI uses to decode the
 * track's *embedded* artwork (ID3 APIC / FLAC picture) via
 * `MediaMetadataRetriever.getEmbeddedPicture()`. Embedded art is per-track, so
 * it is the only source that guarantees one track -> one unique cover.
 *
 * `albumArtUri` = the legacy album-level URI, kept only as a fallback for tracks
 * with no embedded picture:
 *     content://media/external/audio/albumart/<albumId>
 * NOTE: this album-level URI does NOT reflect per-track embedded art and is
 * unreliable on several OEM builds (Samsung/Xiaomi/Oppo/Vivo), which is exactly
 * why it must never be the primary artwork source. The UI layer tries embedded
 * art first and only falls back to this when no embedded picture exists.
 */
object MediaStoreScanner {

    private const val TAG = "MediaStoreScanner"

    fun scan(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val result = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(dataCol) ?: ""
                val folder = File(path).parentFile?.name ?: ""
                val contentUri = ContentUris.withAppendedId(collection, id).toString()
                val albumId = c.getLong(albumIdCol)
                // Album-level fallback only. Embedded (per-track) art is read
                // from `uri`/`contentUri` by the UI via MediaMetadataRetriever.
                val albumArtUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()

                // Diagnostic log: trackId, albumId, content uri (embedded-art
                // source), and the album-level fallback uri. Inspect in logcat
                // (tag = MediaStoreScanner) to confirm two different track ids
                // do not collapse to the same embedded/album art.
                Log.d(
                    TAG,
                    "trackId=$id albumId=$albumId uri=$contentUri albumArtUri=$albumArtUri title=${c.getString(titleCol)}"
                )

                result += Track(
                    id = id,
                    title = c.getString(titleCol) ?: "<unknown>",
                    artist = c.getString(artistCol).orEmpty(),
                    album = c.getString(albumCol).orEmpty(),
                    durationMs = c.getLong(durationCol),
                    uri = contentUri,
                    albumArtUri = albumArtUri,
                    folder = folder,
                    dateAddedSec = c.getLong(dateCol)
                )
            }
        }
        return result
    }
}
