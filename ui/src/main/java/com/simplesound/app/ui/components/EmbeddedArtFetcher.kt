package com.simplesound.app.ui.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
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

/** Reads the raw embedded picture bytes (or the fallback's) and hands them to Coil to decode. */
class EmbeddedArtFetcher(
    private val data: EmbeddedArt,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = readEmbeddedPicture() ?: readFallback() ?: throw IOException("No artwork for ${data.trackUri}")
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
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
        ): Fetcher = EmbeddedArtFetcher(data, options)
    }
}

/** Lets Coil memory-cache [EmbeddedArt] results (custom models aren't cached without a Keyer). */
class EmbeddedArtKeyer : Keyer<EmbeddedArt> {
    override fun key(
        data: EmbeddedArt,
        options: Options,
    ): String = "embedded:${data.trackUri}|${data.fallbackUri.orEmpty()}"
}
