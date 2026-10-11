package com.simplesound.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import okio.Buffer
import java.io.IOException

/**
 * Coil model for a track's artwork: the picture embedded in the audio file itself
 * ([trackUri], one track -> one cover), falling back to the album-level
 * [fallbackUri] when the file has none. Same priority as before (see
 * MediaStoreScanner's "Artwork strategy" doc comment), but decoding now goes through
 * Coil, which downsamples to the size actually drawn and caches by memory footprint.
 * The previous hand-rolled cache decoded every cover at full resolution (a 3000 px
 * cover is ~36 MB) and kept up to 256 of them.
 */
data class EmbeddedArt(
    val trackUri: String,
    val fallbackUri: String?,
)

/**
 * Reads the raw embedded picture bytes (or the fallback's) and hands them to Coil to
 * decode.
 *
 * Reading a cover means opening the audio file, parsing its tags and decoding a
 * picture that is often 1000-3000 px, for a row drawn ~150 px wide. Without a disk
 * cache that ran again for every visible row on every launch and on every memory-cache
 * miss while scrolling, so the result is kept in Coil's [DiskCache] as a small JPEG
 * (or an empty "no artwork" marker) per size bucket. Entries are stamped with the
 * file's MediaStore modification time and size, so a cover changed by a tag editor is
 * read afresh instead of served stale.
 */
class EmbeddedArtFetcher(
    private val data: EmbeddedArt,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = diskCache
        val stamp = cache?.let { sourceStamp() }
        return if (cache == null || stamp == null) sourceOf(readArtwork()) else fetchCached(cache, stamp)
    }

    private fun fetchCached(
        cache: DiskCache,
        stamp: String,
    ): FetchResult {
        val key = "embedded-thumb:${data.trackUri}|${data.fallbackUri.orEmpty()}|${sizeBucket()}"
        val snapshot =
            validSnapshot(cache, key, stamp) ?: run {
                val bytes = readArtwork()
                store(cache, key, stamp, bytes) ?: return sourceOf(bytes)
            }
        if (cache.fileSystem.metadata(snapshot.data).size == 0L) {
            snapshot.close()
            throw noArtwork()
        }
        return SourceResult(
            source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /** The cached entry for [key] if it was made from the file as it is now ([stamp]). */
    private fun validSnapshot(
        cache: DiskCache,
        key: String,
        stamp: String,
    ): DiskCache.Snapshot? {
        val snapshot = cache.openSnapshot(key) ?: return null
        val current = runCatching { cache.fileSystem.read(snapshot.metadata) { readUtf8() } }.getOrNull() == stamp
        if (!current) snapshot.close()
        return snapshot.takeIf { current }
    }

    /**
     * Caches [bytes] as a thumbnail, or as an empty "no artwork" marker when null, so
     * the file isn't re-read every time. Null if another request is writing the same
     * entry right now or the write fails; the caller then serves [bytes] uncached.
     */
    private fun store(
        cache: DiskCache,
        key: String,
        stamp: String,
        bytes: ByteArray?,
    ): DiskCache.Snapshot? {
        val editor = cache.openEditor(key) ?: return null
        return runCatching {
            val thumbnail = bytes?.let { thumbnailOf(it) }
            cache.fileSystem.write(editor.metadata) { writeUtf8(stamp) }
            cache.fileSystem.write(editor.data) { thumbnail?.let { write(it) } }
            editor.commitAndOpenSnapshot()
        }.onFailure { editor.abort() }.getOrNull()
    }

    private fun sourceOf(bytes: ByteArray?): FetchResult =
        SourceResult(
            source = ImageSource(Buffer().write(bytes ?: throw noArtwork()), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )

    private fun noArtwork() = IOException("No artwork for ${data.trackUri}")

    private fun readArtwork(): ByteArray? = readEmbeddedPicture() ?: readFallback()

    /**
     * "modified:size" of the track file per MediaStore, or null (no caching) when it
     * can't be read, e.g. a non-MediaStore uri.
     */
    private fun sourceStamp(): String? =
        runCatching {
            options.context.contentResolver.query(
                Uri.parse(data.trackUri),
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) "${c.getLong(0)}:${c.getLong(1)}" else null }
        }.getOrNull()

    /** Longest side a thumbnail is stored at; a few buckets keep rows, the carousel and Now Playing sharp. */
    private fun sizeBucket(): Int {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
        val h = (options.size.height as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
        val wanted = maxOf(w, h)
        return SIZE_BUCKETS.firstOrNull { wanted <= it } ?: SIZE_BUCKETS.last()
    }

    /** [bytes] scaled down to [sizeBucket] and re-encoded as JPEG; null if undecodable. */
    private fun thumbnailOf(bytes: ByteArray): ByteArray? {
        val bucket = sizeBucket()
        val decoded = decodeAtLeast(bytes, bucket) ?: return null
        val scale = bucket.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
        val out = Buffer()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out.outputStream())
        scaled.recycle()
        return out.readByteArray()
    }

    /** Decodes [bytes] with the largest power-of-two subsampling that keeps the longest side >= [size]. */
    private fun decodeAtLeast(
        bytes: ByteArray,
        size: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun readEmbeddedPicture(): ByteArray? {
        val retriever = MediaMetadataRetriever()
        // An unreadable/deleted file or unsupported container throws; fall back
        // rather than fail the row.
        return runCatching {
            retriever.setDataSource(options.context, Uri.parse(data.trackUri))
            retriever.embeddedPicture
        }.getOrNull().also { runCatching { retriever.release() } }
    }

    /**
     * Album-art fallback. Opened as a typed image asset, the same way Coil's own
     * ContentUriFetcher opens `.../audio/albumart/...` URIs: on recent Android versions
     * MediaStore only serves album art as a generated thumbnail, and a plain
     * openInputStream() on these URIs fails.
     */
    private fun readFallback(): ByteArray? {
        val uri = data.fallbackUri ?: return null
        return runCatching {
            options.context.contentResolver
                .openTypedAssetFile(Uri.parse(uri), "image/*", null, null)
                ?.use { afd -> afd.createInputStream().use { it.readBytes() } }
        }.getOrNull()
    }

    class Factory : Fetcher.Factory<EmbeddedArt> {
        override fun create(
            data: EmbeddedArt,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EmbeddedArtFetcher(data, options, imageLoader.diskCache)
    }

    private companion object {
        val SIZE_BUCKETS = intArrayOf(256, 640, 1280)
        const val JPEG_QUALITY = 90
    }
}

/** Lets Coil memory-cache [EmbeddedArt] results (custom models aren't cached without a Keyer). */
class EmbeddedArtKeyer : Keyer<EmbeddedArt> {
    override fun key(
        data: EmbeddedArt,
        options: Options,
    ): String = "embedded:${data.trackUri}|${data.fallbackUri.orEmpty()}"
}
