package com.simplesound.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.simplesound.app.data.model.Track

/**
 * Share a track's audio file via Android's share sheet.
 *
 * [Track.uri] is the MediaStore content URI the app scanned/plays from
 * (`content://media/external/audio/media/<id>`, see MediaStoreScanner's
 * "Artwork strategy" doc comment for why), not a filesystem path — so it is
 * shared directly as a content [Uri] with a read-permission grant. There is
 * no on-disk path to route through [android.net.Uri.parse]'s `file://` form
 * or a `FileProvider`; doing so silently no-ops because the resulting
 * `java.io.File` never exists.
 */
fun shareTrack(
    context: Context,
    track: Track,
) {
    if (track.uri.isBlank()) return
    val uri = Uri.parse(track.uri)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, "Share \"${track.title}\""))
}
