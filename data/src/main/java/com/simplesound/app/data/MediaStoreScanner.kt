package com.simplesound.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.simplesound.app.data.model.Track
import java.io.File

/**
 * Reads the device's audio library from MediaStore. Requires the READ_MEDIA_AUDIO
 * (API 33+) or READ_EXTERNAL_STORAGE permission to be granted; if not granted or
 * empty, callers fall back to [SampleData].
 */
object MediaStoreScanner {

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
                val artUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()

                result += Track(
                    id = id,
                    title = c.getString(titleCol) ?: "<unknown>",
                    artist = c.getString(artistCol).orEmpty(),
                    album = c.getString(albumCol).orEmpty(),
                    durationMs = c.getLong(durationCol),
                    uri = contentUri,
                    albumArtUri = artUri,
                    folder = folder,
                    dateAddedSec = c.getLong(dateCol)
                )
            }
        }
        return result
    }
}
