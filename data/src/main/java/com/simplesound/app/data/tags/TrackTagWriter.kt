package com.simplesound.app.data.tags

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.simplesound.app.data.model.Track
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes edited tags into a track's audio file itself (not just the app's database),
 * so the change shows up in every other player and survives a reinstall.
 *
 * The caller must already hold write access to [Track.uri]: on Android 11+ that's
 * a granted MediaStore.createWriteRequest, on 8–10 WRITE_EXTERNAL_STORAGE. Blocking
 * I/O -- call off the main thread.
 */
object TrackTagWriter {
    /** Only MP3 (ID3) is supported for now. */
    fun supports(track: Track): Boolean = track.path.endsWith(".mp3", ignoreCase = true)

    fun write(
        context: Context,
        track: Track,
        edits: TagEdits,
    ) {
        if (!supports(track)) throw UnsupportedTagException("Only MP3 files can be edited")
        val pfd =
            context.contentResolver.openFileDescriptor(Uri.parse(track.uri), "rw")
                ?: throw IOException("Couldn't open ${track.path}")
        pfd.use {
            // Both streams share pfd's descriptor without owning it; pfd.close() closes it.
            val read = FileInputStream(it.fileDescriptor).channel
            val write = FileOutputStream(it.fileDescriptor).channel
            Id3Tag.applyToFile(read, write, edits, context.cacheDir)
        }
        // Android 11+ rescans a file when a write descriptor MediaProvider handed out is
        // closed; on 8–10 nothing does, so ask for it. Harmless duplicate on 11+.
        if (track.path.isNotBlank()) MediaScannerConnection.scanFile(context, arrayOf(track.path), null, null)
    }
}
