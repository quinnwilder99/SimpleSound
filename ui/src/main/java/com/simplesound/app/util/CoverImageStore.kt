package com.simplesound.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A square region of a source bitmap, in that bitmap's pixel coordinates. */
data class CropRegion(val left: Int, val top: Int, val size: Int)

/**
 * Decodes, crops and persists user-picked playlist cover art.
 *
 * The cover the user picks in the photo picker is not stored as a reference to
 * the original (a `content://` URI whose read grant can be revoked, and whose
 * framing we can't control). Instead the chosen square region is baked into a
 * downscaled JPEG under [coversDir] and that file URI becomes the playlist's
 * `coverUri`. Every place that renders a cover already crops a square with
 * `ContentScale.Crop`, so a square source file just displays as-is.
 */
object CoverImageStore {
    /** Longest edge of the working bitmap handed to the crop UI. */
    private const val MAX_EDIT_DIMENSION = 2048

    /** Side length of the saved square cover, in pixels. */
    private const val OUTPUT_SIZE = 1080

    private const val JPEG_QUALITY = 90

    private fun coversDir(context: Context): File = File(context.filesDir, "playlist_covers").apply { mkdirs() }

    /**
     * Deletes [coverUri] when it is a cover file this app wrote (e.g. the
     * playlist it belonged to is being deleted). A no-op for null, external
     * `content://` covers, or an already-missing file.
     */
    fun deleteIfOwned(
        context: Context,
        coverUri: String?,
    ) {
        if (coverUri != null && isOwnedCover(context, coverUri)) {
            runCatching { Uri.parse(coverUri).path?.let { File(it).delete() } }
        }
    }

    /** True when [uri] points at a cover file this app previously wrote. */
    private fun isOwnedCover(
        context: Context,
        uri: String,
    ): Boolean =
        runCatching {
            val path = Uri.parse(uri).path ?: return false
            File(path).parentFile?.canonicalPath == coversDir(context).canonicalPath
        }.getOrDefault(false)

    /**
     * Loads [source] downsampled so its longest edge is at most
     * [MAX_EDIT_DIMENSION], with EXIF rotation already applied. For display in
     * the crop UI; null if the image can't be read.
     */
    suspend fun loadForEditing(
        context: Context,
        source: Uri,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(source)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext null

            var sample = 1
            while (longest / (sample * 2) >= MAX_EDIT_DIMENSION) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }

            val decoded =
                runCatching {
                    context.contentResolver.openInputStream(source)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }.getOrNull() ?: return@withContext null

            applyExifRotation(context, source, decoded)
        }

    /**
     * Writes [region] of [source] as a downscaled square JPEG and returns its
     * file URI. Deletes [previousCoverUri] when it is a file this app owns, and
     * uses a fresh filename each time so image caches never serve a stale crop.
     */
    suspend fun saveCrop(
        context: Context,
        playlistId: String,
        source: Bitmap,
        region: CropRegion,
        previousCoverUri: String?,
    ): Uri =
        withContext(Dispatchers.IO) {
            val left = region.left.coerceIn(0, (source.width - 1).coerceAtLeast(0))
            val top = region.top.coerceIn(0, (source.height - 1).coerceAtLeast(0))
            val size =
                region.size.coerceAtMost(minOf(source.width - left, source.height - top)).coerceAtLeast(1)

            val square = Bitmap.createBitmap(source, left, top, size, size)
            val scaled =
                if (square.width > OUTPUT_SIZE) {
                    Bitmap.createScaledBitmap(square, OUTPUT_SIZE, OUTPUT_SIZE, true)
                        .also { if (it != square) square.recycle() }
                } else {
                    square
                }

            val safeId = playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val outFile = File(coversDir(context), "$safeId-${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            scaled.recycle()

            deleteIfOwned(context, previousCoverUri)

            Uri.fromFile(outFile)
        }

    private fun applyExifRotation(
        context: Context,
        source: Uri,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation =
            runCatching {
                context.contentResolver.openInputStream(source)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }
}
