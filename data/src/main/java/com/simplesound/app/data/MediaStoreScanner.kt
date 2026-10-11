package com.simplesound.app.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.simplesound.app.data.model.Track

/** See [MediaStoreScanner.fingerprint]; [trackCount] is how many tracks [MediaStoreScanner.scan] would return. */
data class LibraryFingerprint(
    val key: String,
    val trackCount: Int,
)

/**
 * Reads the device's audio library from MediaStore. Requires the READ_MEDIA_AUDIO
 * (API 33+) or READ_EXTERNAL_STORAGE permission to be granted; if not granted or
 * the scan throws, [MusicRepository.loadDeviceLibrary] falls back to an empty list
 * and leaves the previously loaded library untouched.
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
    /**
     * Bump whenever [scan]'s projection, selection or row mapping changes, so every
     * stored [fingerprint] goes stale and the next launch rescans once.
     */
    private const val SCAN_FORMAT = 1

    private const val SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    /**
     * A cheap summary of exactly the rows [scan] reads: equal fingerprints mean a
     * scan would return the same list, so the (much heavier) scan + Room rewrite can
     * be skipped. Any insert or edit raises the max GENERATION_MODIFIED, a removal
     * lowers the row count, and a MediaStore rebuild (which can reassign ids) changes
     * the volume version. The app's own last-update time is included so a new build
     * always rescans once. Null (always scan) before Android 11, which has no
     * generation column, or if anything here fails.
     */
    fun fingerprint(context: Context): LibraryFingerprint? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            null
        } else {
            runCatching {
                var count = 0
                var maxGeneration = 0L
                val cursor =
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media.GENERATION_MODIFIED),
                        SELECTION,
                        null,
                        null,
                    )
                checkNotNull(cursor).use { c ->
                    while (c.moveToNext()) {
                        count++
                        maxGeneration = maxOf(maxGeneration, c.getLong(0))
                    }
                }
                val appUpdated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                LibraryFingerprint(
                    key = "$SCAN_FORMAT|${MediaStore.getVersion(context)}|$count|$maxGeneration|$appUpdated",
                    trackCount = count,
                )
            }.getOrNull()
        }

    fun scan(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
            )
        val selection = SELECTION
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
                val folder = folderOf(path)
                val contentUri = ContentUris.withAppendedId(collection, id).toString()
                val albumId = c.getLong(albumIdCol)
                // Album-level fallback only. Embedded (per-track) art is read
                // from `uri`/`contentUri` by the UI via MediaMetadataRetriever.
                val albumArtUri =
                    ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId,
                    ).toString()

                result +=
                    Track(
                        id = id,
                        title = c.getString(titleCol) ?: "<unknown>",
                        artist = c.getString(artistCol).orEmpty(),
                        album = c.getString(albumCol).orEmpty(),
                        durationMs = c.getLong(durationCol),
                        uri = contentUri,
                        albumArtUri = albumArtUri,
                        folder = folder,
                        dateAddedSec = c.getLong(dateCol),
                        path = path,
                    )
            }
        }
        return result
    }

    private val VOLUME_ROOT = Regex("""^/storage/(emulated/\d+|[^/]+)/""")

    /**
     * The track's parent directory relative to its storage volume, e.g.
     * `/storage/emulated/0/Music/Rap/a.mp3` -> `Music/Rap`. The full relative path
     * (not just the last segment) keeps two different `.../Music` folders from being
     * merged into one entry on the Folders tab.
     */
    internal fun folderOf(path: String): String {
        // Plain string ops rather than java.io.File so this behaves the same in JVM
        // unit tests on a Windows host (File would use '\' separators there).
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        return "$parent/".replaceFirst(VOLUME_ROOT, "").trimEnd('/')
    }
}
