package com.simplesound.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Supplies artwork for the [androidx.media3.session.MediaSession] — this is what
 * ends up on the lock-screen / notification media widget.
 *
 * Mirrors the artwork strategy the in-app UI already follows (see
 * MediaStoreScanner's doc comment and ui/Artwork.kt): a track's *embedded*
 * picture (ID3 APIC / FLAC cover), decoded per-track from the track's own
 * content URI (passed through [MediaMetadata.extras] under
 * [KEY_TRACK_CONTENT_URI]), is the only source that guarantees one track ->
 * one unique cover, so it is tried first.
 *
 * The legacy album-level `content://.../albumart/<albumId>` URI
 * ([MediaMetadata.artworkUri]) is keyed by album, not by track — unrelated
 * tracks (or tracks with missing/incorrect album tagging) can resolve to the
 * same, stale, or wrong art on several OEM builds. That was previously the
 * *only* artwork source fed to the MediaSession, which is why the lock-screen
 * widget could show the wrong cover for many tracks. It is now used only as a
 * fallback when a track has no embedded picture.
 */
@UnstableApi
class TrackArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    // Wrapped in CacheBitmapLoader so the album-art fallback path (`loadBitmap`,
    // called directly from `loadBitmapFromMetadata` below) doesn't refetch the same
    // URI on every notification rebuild -- `DefaultMediaNotificationProvider` calls
    // `loadBitmapFromMetadata` on every notification refresh, not just on track
    // change, so this used to be handled by the platform default's own caching,
    // which is lost by supplying a custom loader here.
    private val delegate = CacheBitmapLoader(DataSourceBitmapLoader(context))

    // Tiny cache so rapid track transitions (skip spam, queue reorders) don't
    // re-decode the same embedded picture over and over; bounded and evicts
    // the least-recently-used entry, same pattern as ui/Artwork.kt's LRU.
    private val cache =
        object : LinkedHashMap<String, Bitmap?>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean =
                size > CACHE_SIZE
        }

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = delegate.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val trackUri = metadata.extras?.getString(KEY_TRACK_CONTENT_URI)
        val albumArtUri = metadata.artworkUri
        if (trackUri.isNullOrBlank() && albumArtUri == null) return null

        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            val embedded = trackUri?.let { loadEmbedded(it) }
            when {
                embedded != null -> future.set(embedded)
                albumArtUri != null -> future.setFuture(delegate.loadBitmap(albumArtUri))
                else -> future.setException(IllegalStateException("No artwork for $trackUri"))
            }
        }
        return future
    }

    /** Must only be called from [executor]. */
    private fun loadEmbedded(trackUri: String): Bitmap? {
        synchronized(cache) {
            if (cache.containsKey(trackUri)) return cache[trackUri]
        }
        val retriever = MediaMetadataRetriever()
        val bitmap =
            try {
                retriever.setDataSource(context, Uri.parse(trackUri))
                val bytes = retriever.embeddedPicture
                bytes?.let { decodeDownsampled(it) }
            } catch (e: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        synchronized(cache) { cache[trackUri] = bitmap }
        return bitmap
    }

    /**
     * Embedded cover art from modern taggers can be several thousand pixels per
     * side (tens of MB as an ARGB_8888 [Bitmap]); the lock-screen/notification
     * widget only ever displays this at a small fixed size. Decoding bounds-only
     * first and picking an [BitmapFactory.Options.inSampleSize] avoids allocating
     * a full-resolution bitmap per track, which combined with [CACHE_SIZE] cached
     * entries was a real OOM risk.
     */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= TARGET_ART_SIZE_PX &&
            bounds.outHeight / (sampleSize * 2) >= TARGET_ART_SIZE_PX
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        const val KEY_TRACK_CONTENT_URI = "com.simplesound.app.TRACK_CONTENT_URI"
        private const val CACHE_SIZE = 8
        private const val TARGET_ART_SIZE_PX = 512
        private val executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "TrackArtworkBitmapLoader").apply { isDaemon = true }
            }
    }
}
